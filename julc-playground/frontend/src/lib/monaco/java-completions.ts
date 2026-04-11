import type * as Monaco from 'monaco-editor';
import { METHOD_DOCS, ANNOTATION_DOCS, TYPE_DOCS, INSTANCE_METHODS, type InstanceMethodInfo } from './java-docs';

const JAVA_LANGUAGE_ID = 'java';

// --- Library source integration ---
let currentLibrarySource = '';

/** Update the library source for autocomplete integration. Called from Editor.svelte. */
export function setLibrarySource(source: string) {
  currentLibrarySource = source;
}

// --- Source analysis helpers ---

/** Extract return type from a method signature like "getTxInfo(ScriptContext ctx): TxInfo" */
function extractReturnType(signature: string): string | null {
  const m = signature.match(/:\s*(\w+(?:<[^>]*>)?)\s*$/);
  return m ? m[1] : null;
}

/** Extract variable-to-type mappings from source text via regex. */
function extractVariableTypes(source: string): Map<string, string> {
  const types = new Map<string, string>();

  // Pass 1: Explicit type declarations (Type varName = ..., Type varName, etc.)
  const declPattern = /\b([A-Z]\w*(?:<[^>]*>)?)\s+([a-z]\w*)\s*[=;,)]/g;
  let match;
  while ((match = declPattern.exec(source)) !== null) {
    types.set(match[2], match[1]);
  }

  // Pass 1b: for-each loops
  const forEachPattern = /for\s*\(\s*([A-Z]\w*(?:<[^>]*>)?)\s+([a-z]\w*)\s*:/g;
  while ((match = forEachPattern.exec(source)) !== null) {
    types.set(match[2], match[1]);
  }

  // Pass 2: Refine PlutusData/var types by inferring from method call return types
  // Handles: PlutusData x = Cls.method(...) and var x = Cls.method(...)
  const inferPattern = /\b(?:PlutusData|var)\s+(\w+)\s*=\s*(\w+)\.(\w+)\s*\(/g;
  while ((match = inferPattern.exec(source)) !== null) {
    const varName = match[1];
    const receiver = match[2];
    const methodName = match[3];

    // Try static method return type from docs
    const classDocs = METHOD_DOCS[receiver];
    if (classDocs?.[methodName]) {
      const retType = extractReturnType(classDocs[methodName].signature);
      if (retType && retType !== 'PlutusData' && retType !== 'void') {
        types.set(varName, retType);
        continue;
      }
    }

    // Try instance method return type (variable.method)
    const receiverType = types.get(receiver);
    if (receiverType) {
      const baseType = normalizeBaseType(receiverType);
      const methods = INSTANCE_METHODS[baseType];
      const method = methods?.find(m => m.name === methodName);
      if (method && method.returnType !== 'PlutusData') {
        types.set(varName, method.returnType);
      }
    }
  }

  return types;
}

/** Extract user-defined record types and their field accessors from source. */
function extractRecordTypes(source: string): Record<string, InstanceMethodInfo[]> {
  const records: Record<string, InstanceMethodInfo[]> = {};
  const recordPattern = /record\s+(\w+)\s*\(([^)]*)\)/g;
  let match;
  while ((match = recordPattern.exec(source)) !== null) {
    const typeName = match[1];
    const paramsStr = match[2].trim();
    if (!paramsStr) continue;

    const fields: InstanceMethodInfo[] = [];
    for (const param of paramsStr.split(',')) {
      const fieldMatch = param.trim().match(/^(\S+)\s+(\w+)$/);
      if (fieldMatch) {
        fields.push({
          name: fieldMatch[2],
          returnType: fieldMatch[1],
          detail: `${typeName} field`,
        });
      }
    }
    if (fields.length > 0) {
      records[typeName] = fields;
    }
  }
  return records;
}

/** Extract class names and static methods from library source. */
function extractLibraryMethods(librarySource: string): Record<string, InstanceMethodInfo[]> {
  if (!librarySource?.trim()) return {};
  const result: Record<string, InstanceMethodInfo[]> = {};

  const classMatch = librarySource.match(/class\s+(\w+)/);
  if (!classMatch) return result;
  const className = classMatch[1];

  const methodPattern = /static\s+(\w+(?:<[^>]*>)?)\s+(\w+)\s*\(([^)]*)\)/g;
  const methods: InstanceMethodInfo[] = [];
  let match;
  while ((match = methodPattern.exec(librarySource)) !== null) {
    const returnType = match[1];
    const methodName = match[2];
    const params = match[3].trim();

    let insertText: string | undefined;
    if (params) {
      const snippetParams = params.split(',').map((p, i) => {
        const parts = p.trim().split(/\s+/);
        const name = parts.length > 1 ? parts[parts.length - 1] : `arg${i}`;
        return `\${${i + 1}:${name}}`;
      }).join(', ');
      insertText = `${methodName}(${snippetParams})`;
    }

    const sig = params ? `${methodName}(${params}): ${returnType}` : `${methodName}(): ${returnType}`;
    methods.push({ name: methodName, returnType, detail: `${className} library method`, insertText, signature: sig });
  }

  if (methods.length > 0) {
    result[className] = methods;
  }
  return result;
}

/** Normalize collection type aliases to their canonical base names. */
function normalizeBaseType(type: string): string {
  const base = type.replace(/<.*>/, '');
  if (base === 'JulcList') return 'List';
  if (base === 'JulcMap') return 'Map';
  return base;
}

/** Convert InstanceMethodInfo[] to Monaco completion items. */
function toCompletionItems(
  monaco: typeof Monaco,
  range: Monaco.IRange,
  methods: InstanceMethodInfo[],
): Monaco.languages.CompletionItem[] {
  return methods.map((m, i) => {
    const hasSnippet = !!m.insertText;
    return {
      label: m.name,
      kind: monaco.languages.CompletionItemKind.Method,
      detail: `${m.returnType} — ${m.detail}`,
      insertText: m.insertText || `${m.name}()`,
      ...(hasSnippet ? { insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet } : {}),
      sortText: `0${String(i).padStart(2, '0')}`,
      range,
    } as Monaco.languages.CompletionItem;
  });
}

// --- Registration ---

export function registerJavaCompletions(monaco: typeof Monaco) {
  monaco.languages.registerCompletionItemProvider(JAVA_LANGUAGE_ID, {
    triggerCharacters: ['.', '@'],
    provideCompletionItems(model, position) {
      const word = model.getWordUntilPosition(position);
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn,
      };

      const lineContent = model.getLineContent(position.lineNumber);
      const beforeCursor = lineContent.substring(0, position.column - 1);

      // --- Dot-triggered completions ---
      const dotMatch = beforeCursor.match(/(\w+)\.\s*(\w*)$/);
      if (dotMatch) {
        const receiver = dotMatch[1];
        const partialWord = dotMatch[2] || '';
        const methodRange = {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: position.column - partialWord.length,
          endColumn: position.column,
        };

        // 1. Static stdlib method completions (ContextsLib., MapLib., etc.)
        const staticMethods = staticMethodCompletions(monaco, methodRange, receiver);
        if (staticMethods.length > 0) {
          return { suggestions: staticMethods };
        }

        const source = model.getValue();

        // 2. Instance methods on typed variables (ctx.txInfo, datum.owner, outputs.head)
        const varTypes = extractVariableTypes(source);
        const varType = varTypes.get(receiver);
        if (varType) {
          const baseType = normalizeBaseType(varType);
          const builtinMethods = INSTANCE_METHODS[baseType] || [];
          const userRecords = extractRecordTypes(source);
          const recordMethods = userRecords[baseType] || [];
          const allMethods = [...builtinMethods, ...recordMethods];
          if (allMethods.length > 0) {
            return { suggestions: toCompletionItems(monaco, methodRange, allMethods) };
          }
        }

        // 3. Type name used as receiver (ScriptContext., Value., etc.)
        const typeMethods = INSTANCE_METHODS[receiver];
        if (typeMethods && typeMethods.length > 0) {
          return { suggestions: toCompletionItems(monaco, methodRange, typeMethods) };
        }

        // 4. User-defined record type used as receiver (MyDatum.)
        const userRecords = extractRecordTypes(source);
        if (userRecords[receiver]?.length > 0) {
          return { suggestions: toCompletionItems(monaco, methodRange, userRecords[receiver]) };
        }

        // 5. Library class methods (MyHelper.)
        const libMethods = extractLibraryMethods(currentLibrarySource);
        if (libMethods[receiver]?.length > 0) {
          return { suggestions: toCompletionItems(monaco, methodRange, libMethods[receiver]) };
        }
      }

      // --- Annotation completions ---
      if (beforeCursor.match(/@\w*$/)) {
        return { suggestions: annotationCompletions(monaco, range) };
      }

      // --- General completions ---
      return {
        suggestions: [
          ...annotationCompletions(monaco, range),
          ...stdlibClasses(monaco, range),
          ...ledgerTypes(monaco, range),
          ...coreTypes(monaco, range),
          ...snippets(monaco, range),
          ...libraryClassCompletions(monaco, range),
        ],
      };
    },
  });
}

export function registerJavaHoverProvider(monaco: typeof Monaco) {
  monaco.languages.registerHoverProvider(JAVA_LANGUAGE_ID, {
    provideHover(model, position) {
      const word = model.getWordAtPosition(position);
      if (!word) return null;

      const lineContent = model.getLineContent(position.lineNumber);
      const wordText = word.word;
      const beforeWord = lineContent.substring(0, word.startColumn - 1);
      const hoverRange = new monaco.Range(
        position.lineNumber, word.startColumn,
        position.lineNumber, word.endColumn,
      );

      // Case 1: Cursor on method/field name after "Receiver."
      const dotBefore = beforeWord.match(/(\w+)\.$/);
      if (dotBefore) {
        const prefix = dotBefore[1];

        // 1a. Static method docs (ContextsLib.getTxInfo)
        const classDocs = METHOD_DOCS[prefix];
        if (classDocs?.[wordText]) {
          const doc = classDocs[wordText];
          return {
            range: hoverRange,
            contents: [
              { value: `**${prefix}.${wordText}**` },
              { value: '```java\n' + doc.signature + '\n```' },
              { value: doc.description },
            ],
          };
        }

        // 1b. Instance method on variable (ctx.txInfo, datum.owner)
        const source = model.getValue();
        const varTypes = extractVariableTypes(source);
        const varType = varTypes.get(prefix);
        if (varType) {
          const baseType = normalizeBaseType(varType);
          const builtinMethods = INSTANCE_METHODS[baseType] || [];
          const userRecords = extractRecordTypes(source);
          const recordMethods = userRecords[baseType] || [];
          const method = [...builtinMethods, ...recordMethods].find(m => m.name === wordText);
          if (method) {
            return {
              range: hoverRange,
              contents: [
                { value: `**${varType}.${wordText}**` },
                { value: '```java\n' + `${method.returnType} ${wordText}()` + '\n```' },
                { value: method.detail },
              ],
            };
          }
        }

        // 1c. Library method (MyHelper.compute)
        const libMethods = extractLibraryMethods(currentLibrarySource);
        const libMethod = libMethods[prefix]?.find(m => m.name === wordText);
        if (libMethod) {
          return {
            range: hoverRange,
            contents: [
              { value: `**${prefix}.${wordText}** *(library)*` },
              { value: '```java\n' + `${libMethod.returnType} ${wordText}(...)` + '\n```' },
              { value: libMethod.detail },
            ],
          };
        }
      }

      // Case 2: Cursor on stdlib class name before ".method"
      const afterWord = lineContent.substring(word.endColumn - 1);
      if (afterWord.startsWith('.') && METHOD_DOCS[wordText]) {
        return {
          range: hoverRange,
          contents: [
            { value: `**${wordText}**` },
            { value: `JuLC stdlib class — ${Object.keys(METHOD_DOCS[wordText]).length} methods` },
          ],
        };
      }

      // Case 3: Annotation (@Validator, @Entrypoint, etc.)
      if (word.startColumn > 1 && lineContent[word.startColumn - 2] === '@') {
        const annDoc = ANNOTATION_DOCS[wordText];
        if (annDoc) {
          return {
            range: new monaco.Range(position.lineNumber, word.startColumn - 1, position.lineNumber, word.endColumn),
            contents: [{ value: `**@${wordText}**` }, { value: annDoc }],
          };
        }
      }

      // Case 4: Type name (ScriptContext, TxInfo, etc.)
      const typeDoc = TYPE_DOCS[wordText];
      if (typeDoc) {
        const fieldsStr = typeDoc.fields.length > 0
          ? '\n\n**Fields:**\n' + typeDoc.fields.map(f => `- \`${f}\``).join('\n')
          : '';
        return {
          range: hoverRange,
          contents: [{ value: `**${wordText}**` }, { value: typeDoc.description + fieldsStr }],
        };
      }

      return null;
    },
  });
}

// --- Completion data helpers ---

function annotationCompletions(monaco: typeof Monaco, range: Monaco.IRange): Monaco.languages.CompletionItem[] {
  return [
    { label: '@Validator', detail: 'Generic validator (purpose from param count)', insertText: 'Validator' },
    { label: '@SpendingValidator', detail: 'Spending validator (2-3 params)', insertText: 'SpendingValidator' },
    { label: '@MintingPolicy', detail: 'Minting policy (2 params)', insertText: 'MintingPolicy' },
    { label: '@MintingValidator', detail: 'Minting validator (2 params)', insertText: 'MintingValidator' },
    { label: '@WithdrawValidator', detail: 'Withdraw validator', insertText: 'WithdrawValidator' },
    { label: '@CertifyingValidator', detail: 'Certifying validator', insertText: 'CertifyingValidator' },
    { label: '@VotingValidator', detail: 'Voting validator', insertText: 'VotingValidator' },
    { label: '@ProposingValidator', detail: 'Proposing validator', insertText: 'ProposingValidator' },
    { label: '@MultiValidator', detail: 'Multi-purpose validator', insertText: 'MultiValidator' },
    { label: '@Entrypoint', detail: 'Validator entrypoint method', insertText: 'Entrypoint' },
    { label: '@Param', detail: 'Compile-time parameter', insertText: 'Param' },
    { label: '@OnchainLibrary', detail: 'On-chain library class (reusable across validators)', insertText: 'OnchainLibrary' },
  ].map(a => ({
    ...a,
    kind: monaco.languages.CompletionItemKind.Interface,
    range,
  }));
}

function stdlibClasses(monaco: typeof Monaco, range: Monaco.IRange): Monaco.languages.CompletionItem[] {
  return [
    { label: 'ContextsLib', detail: 'Transaction context utilities' },
    { label: 'ListsLib', detail: 'List operations' },
    { label: 'MapLib', detail: 'Map operations' },
    { label: 'ValuesLib', detail: 'Value comparison and arithmetic' },
    { label: 'OutputLib', detail: 'Transaction output utilities' },
    { label: 'MathLib', detail: 'Math operations (abs, pow, min, max)' },
    { label: 'IntervalLib', detail: 'Time interval operations' },
    { label: 'CryptoLib', detail: 'Cryptographic operations' },
    { label: 'ByteStringLib', detail: 'Byte string operations' },
    { label: 'BitwiseLib', detail: 'Bitwise operations' },
    { label: 'AddressLib', detail: 'Address utilities' },
    { label: 'Builtins', detail: 'Low-level Plutus builtins' },
  ].map(c => ({
    ...c,
    kind: monaco.languages.CompletionItemKind.Module,
    insertText: c.label,
    range,
  }));
}

function ledgerTypes(monaco: typeof Monaco, range: Monaco.IRange): Monaco.languages.CompletionItem[] {
  return [
    'ScriptContext', 'TxInfo', 'TxOut', 'TxOutRef', 'Address', 'Value',
    'Credential', 'PubKeyHash', 'ScriptHash', 'ValidatorHash',
    'PolicyId', 'TokenName', 'DatumHash', 'TxId',
    'OutputDatum', 'Interval', 'IntervalBound',
    'ScriptInfo', 'ScriptPurpose',
    'Vote', 'DRep', 'Voter', 'TxCert', 'GovernanceAction',
    'ProposalProcedure', 'Committee', 'StakingCredential', 'Delegatee',
  ].map(t => ({
    label: t,
    kind: monaco.languages.CompletionItemKind.Class,
    detail: 'Ledger type',
    insertText: t,
    range,
  }));
}

function coreTypes(monaco: typeof Monaco, range: Monaco.IRange): Monaco.languages.CompletionItem[] {
  return [
    { label: 'PlutusData', detail: 'Base on-chain data type' },
    { label: 'BigInteger', detail: 'On-chain integer type' },
    { label: 'JulcList', detail: 'On-chain list (JulcList<T>)' },
    { label: 'JulcMap', detail: 'On-chain map (JulcMap<K,V>)' },
    { label: 'Tuple2', detail: 'Pair type (Tuple2<A,B>)' },
    { label: 'Tuple3', detail: 'Triple type (Tuple3<A,B,C>)' },
    { label: 'Optional', detail: 'Optional value' },
  ].map(t => ({
    ...t,
    kind: monaco.languages.CompletionItemKind.Class,
    insertText: t.label,
    range,
  }));
}

/** Dynamic completions for library class names (appears in general suggestions). */
function libraryClassCompletions(monaco: typeof Monaco, range: Monaco.IRange): Monaco.languages.CompletionItem[] {
  const libMethods = extractLibraryMethods(currentLibrarySource);
  return Object.keys(libMethods).map(className => ({
    label: className,
    kind: monaco.languages.CompletionItemKind.Module,
    detail: 'Library class',
    insertText: className,
    range,
  }));
}

function staticMethodCompletions(monaco: typeof Monaco, range: Monaco.IRange, receiver: string): Monaco.languages.CompletionItem[] {
  const methods: Record<string, { label: string; detail: string; insertText: string }[]> = {
    ContextsLib: [
      { label: 'getTxInfo', detail: 'Get TxInfo from ScriptContext', insertText: 'getTxInfo(${1:ctx})' },
      { label: 'signedBy', detail: 'Check if tx is signed by pubkey', insertText: 'signedBy(${1:txInfo}, ${2:pkh})' },
      { label: 'txInfoMint', detail: 'Get minted value from TxInfo', insertText: 'txInfoMint(${1:txInfo})' },
      { label: 'txInfoFee', detail: 'Get fee from TxInfo', insertText: 'txInfoFee(${1:txInfo})' },
      { label: 'txInfoId', detail: 'Get TxId from TxInfo', insertText: 'txInfoId(${1:txInfo})' },
      { label: 'findOwnInput', detail: 'Find own input UTxO', insertText: 'findOwnInput(${1:ctx})' },
      { label: 'getContinuingOutputs', detail: 'Get outputs back to this script', insertText: 'getContinuingOutputs(${1:ctx})' },
      { label: 'valueSpent', detail: 'Total value of all inputs', insertText: 'valueSpent(${1:txInfo})' },
      { label: 'valuePaid', detail: 'Value paid to address', insertText: 'valuePaid(${1:txInfo}, ${2:addr})' },
      { label: 'ownHash', detail: 'Own validator hash', insertText: 'ownHash(${1:ctx})' },
    ],
    ListsLib: [
      { label: 'length', detail: 'List length', insertText: 'length(${1:list})' },
      { label: 'isEmpty', detail: 'Check if list is empty', insertText: 'isEmpty(${1:list})' },
      { label: 'head', detail: 'First element', insertText: 'head(${1:list})' },
      { label: 'tail', detail: 'All elements except first', insertText: 'tail(${1:list})' },
      { label: 'reverse', detail: 'Reverse list', insertText: 'reverse(${1:list})' },
      { label: 'contains', detail: 'Check if element exists', insertText: 'contains(${1:list}, ${2:elem})' },
      { label: 'nth', detail: 'Get element at index', insertText: 'nth(${1:list}, ${2:index})' },
    ],
    ValuesLib: [
      { label: 'leq', detail: 'Value less than or equal', insertText: 'leq(${1:a}, ${2:b})' },
      { label: 'geqMultiAsset', detail: 'Multi-asset greater or equal', insertText: 'geqMultiAsset(${1:a}, ${2:b})' },
      { label: 'isZero', detail: 'Check if value is zero', insertText: 'isZero(${1:val})' },
      { label: 'singleton', detail: 'Create single-asset value', insertText: 'singleton(${1:policy}, ${2:token}, ${3:amount})' },
      { label: 'add', detail: 'Add two values', insertText: 'add(${1:a}, ${2:b})' },
      { label: 'subtract', detail: 'Subtract values', insertText: 'subtract(${1:a}, ${2:b})' },
    ],
    OutputLib: [
      { label: 'txOutAddress', detail: 'Get address from TxOut', insertText: 'txOutAddress(${1:txOut})' },
      { label: 'txOutValue', detail: 'Get value from TxOut', insertText: 'txOutValue(${1:txOut})' },
      { label: 'txOutDatum', detail: 'Get datum from TxOut', insertText: 'txOutDatum(${1:txOut})' },
      { label: 'outputsAt', detail: 'Outputs to address', insertText: 'outputsAt(${1:txInfo}, ${2:addr})' },
      { label: 'lovelacePaidTo', detail: 'Lovelace paid to address', insertText: 'lovelacePaidTo(${1:txInfo}, ${2:addr})' },
    ],
    MathLib: [
      { label: 'abs', detail: 'Absolute value', insertText: 'abs(${1:x})' },
      { label: 'max', detail: 'Maximum of two', insertText: 'max(${1:a}, ${2:b})' },
      { label: 'min', detail: 'Minimum of two', insertText: 'min(${1:a}, ${2:b})' },
      { label: 'pow', detail: 'Power (x^n)', insertText: 'pow(${1:base}, ${2:exp})' },
      { label: 'divMod', detail: 'Division and modulo', insertText: 'divMod(${1:a}, ${2:b})' },
    ],
    MapLib: [
      { label: 'lookup', detail: 'Find value by key (Optional)', insertText: 'lookup(${1:map}, ${2:key})' },
      { label: 'member', detail: 'Check if key exists', insertText: 'member(${1:map}, ${2:key})' },
      { label: 'insert', detail: 'Insert key-value pair', insertText: 'insert(${1:map}, ${2:key}, ${3:val})' },
      { label: 'delete', detail: 'Delete key', insertText: 'delete(${1:map}, ${2:key})' },
      { label: 'keys', detail: 'Get all keys', insertText: 'keys(${1:map})' },
      { label: 'values', detail: 'Get all values', insertText: 'values(${1:map})' },
      { label: 'size', detail: 'Number of entries', insertText: 'size(${1:map})' },
    ],
    IntervalLib: [
      { label: 'between', detail: 'Check if value is in interval', insertText: 'between(${1:interval}, ${2:value})' },
      { label: 'isEmpty', detail: 'Check if interval is empty', insertText: 'isEmpty(${1:interval})' },
    ],
    CryptoLib: [
      { label: 'verifyEcdsaSecp256k1', detail: 'Verify ECDSA signature', insertText: 'verifyEcdsaSecp256k1(${1:key}, ${2:msg}, ${3:sig})' },
      { label: 'verifySchnorrSecp256k1', detail: 'Verify Schnorr signature', insertText: 'verifySchnorrSecp256k1(${1:key}, ${2:msg}, ${3:sig})' },
    ],
    ByteStringLib: [
      { label: 'take', detail: 'Take first n bytes', insertText: 'take(${1:n}, ${2:bs})' },
      { label: 'lessThan', detail: 'Lexicographic less than', insertText: 'lessThan(${1:a}, ${2:b})' },
      { label: 'integerToByteString', detail: 'Convert integer to bytes', insertText: 'integerToByteString(${1:n})' },
    ],
    AddressLib: [
      { label: 'credentialHash', detail: 'Get hash from credential', insertText: 'credentialHash(${1:cred})' },
      { label: 'isScriptAddress', detail: 'Check if address is script', insertText: 'isScriptAddress(${1:addr})' },
      { label: 'isPubKeyAddress', detail: 'Check if address is pubkey', insertText: 'isPubKeyAddress(${1:addr})' },
      { label: 'paymentCredential', detail: 'Get payment credential', insertText: 'paymentCredential(${1:addr})' },
    ],
    Builtins: [
      { label: 'sha2_256', detail: 'SHA2-256 hash', insertText: 'sha2_256(${1:data})' },
      { label: 'sha3_256', detail: 'SHA3-256 hash', insertText: 'sha3_256(${1:data})' },
      { label: 'blake2b_256', detail: 'Blake2b-256 hash', insertText: 'blake2b_256(${1:data})' },
      { label: 'blake2b_224', detail: 'Blake2b-224 hash', insertText: 'blake2b_224(${1:data})' },
      { label: 'trace', detail: 'Trace message for debugging', insertText: 'trace(${1:msg}, ${2:value})' },
      { label: 'equalsData', detail: 'Compare PlutusData', insertText: 'equalsData(${1:a}, ${2:b})' },
      { label: 'iData', detail: 'Wrap integer as Data', insertText: 'iData(${1:n})' },
      { label: 'bData', detail: 'Wrap bytes as Data', insertText: 'bData(${1:bs})' },
      { label: 'unIData', detail: 'Unwrap Data to integer', insertText: 'unIData(${1:data})' },
      { label: 'unBData', detail: 'Unwrap Data to bytes', insertText: 'unBData(${1:data})' },
    ],
  };

  const items = methods[receiver] || [];
  return items.map((m, i) => ({
    label: m.label,
    kind: monaco.languages.CompletionItemKind.Method,
    detail: m.detail,
    insertText: m.insertText,
    insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
    sortText: `0${String(i).padStart(2, '0')}`,
    range,
  }));
}

function snippets(monaco: typeof Monaco, range: Monaco.IRange): Monaco.languages.CompletionItem[] {
  return [
    {
      label: 'spending validator',
      kind: monaco.languages.CompletionItemKind.Snippet,
      detail: 'Spending validator template',
      insertText: [
        'import java.math.BigInteger;',
        '',
        '@SpendingValidator',
        'class ${1:MyValidator} {',
        '    record ${2:MyDatum}(PlutusData ${3:owner}) {}',
        '',
        '    @Entrypoint',
        '    static boolean validate(${2:MyDatum} datum, PlutusData redeemer, ScriptContext ctx) {',
        '        PlutusData txInfo = ContextsLib.getTxInfo(ctx);',
        '        return ContextsLib.signedBy(txInfo, datum.${3:owner}());',
        '    }',
        '}',
      ].join('\n'),
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      range,
    },
    {
      label: 'minting policy',
      kind: monaco.languages.CompletionItemKind.Snippet,
      detail: 'Minting policy template',
      insertText: [
        'import java.math.BigInteger;',
        '',
        '@MintingPolicy',
        'class ${1:MyPolicy} {',
        '    sealed interface ${2:Action} {',
        '        record Mint() implements ${2:Action} {}',
        '        record Burn() implements ${2:Action} {}',
        '    }',
        '',
        '    @Entrypoint',
        '    static boolean validate(PlutusData redeemer, ScriptContext ctx) {',
        '        ${0}',
        '        return true;',
        '    }',
        '}',
      ].join('\n'),
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      range,
    },
    {
      label: '@Entrypoint method',
      kind: monaco.languages.CompletionItemKind.Snippet,
      detail: 'Entrypoint method (spending)',
      insertText: [
        '@Entrypoint',
        'static boolean validate(${1:PlutusData} datum, ${2:PlutusData} redeemer, ScriptContext ctx) {',
        '    ${0}',
        '}',
      ].join('\n'),
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      range,
    },
    {
      label: 'record type',
      kind: monaco.languages.CompletionItemKind.Snippet,
      detail: 'Record type for datum/redeemer',
      insertText: 'record ${1:MyDatum}(${2:PlutusData} ${3:field}) {}',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      range,
    },
    {
      label: 'sealed interface',
      kind: monaco.languages.CompletionItemKind.Snippet,
      detail: 'Sealed interface for sum type redeemer',
      insertText: [
        'sealed interface ${1:Action} {',
        '    record ${2:Variant1}(${3:BigInteger} ${4:field}) implements ${1:Action} {}',
        '    record ${5:Variant2}() implements ${1:Action} {}',
        '}',
      ].join('\n'),
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      range,
    },
  ];
}

// --- Signature Help Provider ---

/** Parse a signature string into parameter info with character offsets. */
function parseSignatureParams(signature: string): { label: [number, number]; documentation: string }[] {
  const openParen = signature.indexOf('(');
  const closeParen = signature.lastIndexOf(')');
  if (openParen < 0 || closeParen < 0) return [];

  const paramsStr = signature.substring(openParen + 1, closeParen);
  if (!paramsStr.trim()) return [];

  // Split by commas, respecting angle brackets for generics
  const params: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < paramsStr.length; i++) {
    const ch = paramsStr[i];
    if (ch === '<') depth++;
    else if (ch === '>') depth--;
    else if (ch === ',' && depth === 0) {
      params.push(paramsStr.substring(start, i).trim());
      start = i + 1;
    }
  }
  params.push(paramsStr.substring(start).trim());

  // Map each param to its offset in the full signature string
  const result: { label: [number, number]; documentation: string }[] = [];
  let searchFrom = openParen + 1;
  for (const param of params) {
    if (!param) continue;
    const idx = signature.indexOf(param, searchFrom);
    if (idx >= 0) {
      result.push({ label: [idx, idx + param.length], documentation: param });
      searchFrom = idx + param.length;
    }
  }
  return result;
}

/** Find the active method call context at the cursor position. */
function findActiveCallContext(text: string): {
  receiver: string | null;
  methodName: string;
  activeParameter: number;
} | null {
  // Scan backwards to find the unmatched open paren
  let depth = 0;
  let parenPos = -1;
  let inString = false;

  for (let i = text.length - 1; i >= 0; i--) {
    const ch = text[i];
    // Simple string literal tracking
    if (ch === '"' && (i === 0 || text[i - 1] !== '\\')) {
      inString = !inString;
      continue;
    }
    if (inString) continue;

    if (ch === ')') depth++;
    else if (ch === '(') {
      if (depth === 0) { parenPos = i; break; }
      depth--;
    }
  }
  if (parenPos < 0) return null;

  // Count commas at depth 0 between parenPos+1 and end of text
  let activeParameter = 0;
  depth = 0;
  inString = false;
  for (let i = parenPos + 1; i < text.length; i++) {
    const ch = text[i];
    if (ch === '"' && (i === 0 || text[i - 1] !== '\\')) {
      inString = !inString;
      continue;
    }
    if (inString) continue;
    if (ch === '(') depth++;
    else if (ch === ')') depth--;
    else if (ch === ',' && depth === 0) activeParameter++;
  }

  // Extract method name and optional receiver before the open paren
  const beforeParen = text.substring(0, parenPos).trimEnd();
  const dotCall = beforeParen.match(/(\w+)\.(\w+)$/);
  if (dotCall) {
    return { receiver: dotCall[1], methodName: dotCall[2], activeParameter };
  }
  const plainCall = beforeParen.match(/(\w+)$/);
  if (plainCall) {
    return { receiver: null, methodName: plainCall[1], activeParameter };
  }
  return null;
}

/** Resolve a method signature string and description for parameter hints. */
function resolveMethodSignature(
  receiver: string | null,
  methodName: string,
  source: string,
): { signature: string; description: string } | null {
  if (receiver) {
    // 1. Static stdlib methods
    const classDocs = METHOD_DOCS[receiver];
    if (classDocs?.[methodName]) {
      return { signature: classDocs[methodName].signature, description: classDocs[methodName].description };
    }

    // 2. Library methods
    const libMethods = extractLibraryMethods(currentLibrarySource);
    const libMethod = libMethods[receiver]?.find(m => m.name === methodName);
    if (libMethod?.signature) {
      return { signature: libMethod.signature, description: libMethod.detail };
    }

    // 3. Instance methods on typed variables
    const varTypes = extractVariableTypes(source);
    const varType = varTypes.get(receiver);
    if (varType) {
      const baseType = normalizeBaseType(varType);
      const methods = INSTANCE_METHODS[baseType] || [];
      const userRecords = extractRecordTypes(source);
      const recordMethods = userRecords[baseType] || [];
      const method = [...methods, ...recordMethods].find(m => m.name === methodName);
      if (method?.signature) {
        return { signature: method.signature, description: method.detail };
      }
      // For instance methods with insertText, reconstruct signature from snippet
      if (method?.insertText) {
        const paramStr = method.insertText.replace(/\$\{\d+:([^}]+)\}/g, '$1');
        const paramsOnly = paramStr.match(/\(([^)]*)\)/)?.[1] || '';
        return { signature: `${methodName}(${paramsOnly}): ${method.returnType}`, description: method.detail };
      }
    }
  }
  return null;
}

export function registerJavaSignatureHelpProvider(monaco: typeof Monaco) {
  monaco.languages.registerSignatureHelpProvider(JAVA_LANGUAGE_ID, {
    signatureHelpTriggerCharacters: ['('],
    signatureHelpRetriggerCharacters: [','],
    provideSignatureHelp(model, position) {
      const textUntilCursor = model.getValueInRange({
        startLineNumber: 1, startColumn: 1,
        endLineNumber: position.lineNumber, endColumn: position.column,
      });

      const ctx = findActiveCallContext(textUntilCursor);
      if (!ctx) return null;

      const source = model.getValue();
      const sig = resolveMethodSignature(ctx.receiver, ctx.methodName, source);
      if (!sig) return null;

      const params = parseSignatureParams(sig.signature);
      const activeParam = Math.min(ctx.activeParameter, Math.max(params.length - 1, 0));

      return {
        value: {
          signatures: [{
            label: sig.signature,
            documentation: sig.description,
            parameters: params,
          }],
          activeSignature: 0,
          activeParameter: activeParam,
        },
        dispose() {},
      };
    },
  });
}

// --- Definition Provider ---

export function registerJavaDefinitionProvider(monaco: typeof Monaco) {
  monaco.languages.registerDefinitionProvider(JAVA_LANGUAGE_ID, {
    provideDefinition(model, position) {
      const word = model.getWordAtPosition(position);
      if (!word) return null;
      const name = word.word;

      // Skip built-in types (no source definition to jump to)
      if (TYPE_DOCS[name] || METHOD_DOCS[name]) return null;

      const source = model.getValue();
      const locations: { uri: any; range: any }[] = [];

      // Search for: record Name(
      const recordRe = new RegExp('\\brecord\\s+' + name + '\\s*\\(', 'g');
      let match;
      while ((match = recordRe.exec(source)) !== null) {
        const pos = model.getPositionAt(match.index + match[0].indexOf(name));
        locations.push({
          uri: model.uri,
          range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column + name.length),
        });
      }

      // Search for: sealed interface Name
      const sealedRe = new RegExp('\\bsealed\\s+interface\\s+' + name + '\\b', 'g');
      while ((match = sealedRe.exec(source)) !== null) {
        const pos = model.getPositionAt(match.index + match[0].indexOf(name));
        locations.push({
          uri: model.uri,
          range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column + name.length),
        });
      }

      // Search for: class Name
      const classRe = new RegExp('\\bclass\\s+' + name + '\\b', 'g');
      while ((match = classRe.exec(source)) !== null) {
        const pos = model.getPositionAt(match.index + match[0].indexOf(name));
        locations.push({
          uri: model.uri,
          range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column + name.length),
        });
      }

      return locations.length > 0 ? locations : null;
    },
  });
}
