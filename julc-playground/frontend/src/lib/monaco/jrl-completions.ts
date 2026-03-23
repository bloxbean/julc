import type * as Monaco from 'monaco-editor';
import { JRL_LANGUAGE_ID } from './jrl-language';

export function registerJrlCompletions(monaco: typeof Monaco) {
  monaco.languages.registerCompletionItemProvider(JRL_LANGUAGE_ID, {
    provideCompletionItems(model, position) {
      const word = model.getWordUntilPosition(position);
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn,
      };

      const suggestions: Monaco.languages.CompletionItem[] = [
        // Structure
        ...['contract', 'version', 'purpose', 'params:', 'datum', 'record', 'redeemer', 'rule', 'default: deny', 'default: allow'].map(k => ({
          label: k,
          kind: monaco.languages.CompletionItemKind.Keyword,
          insertText: k,
          range,
        })),
        // Clauses
        ...['when', 'then allow', 'then deny'].map(k => ({
          label: k,
          kind: monaco.languages.CompletionItemKind.Keyword,
          insertText: k,
          range,
        })),
        // Purpose types
        ...['spending', 'minting', 'withdraw', 'certifying', 'voting', 'proposing'].map(k => ({
          label: k,
          kind: monaco.languages.CompletionItemKind.EnumMember,
          insertText: k,
          range,
        })),
        // Fact patterns
        ...snippets(monaco, range),
        // Types
        ...['Integer', 'Lovelace', 'POSIXTime', 'ByteString', 'Boolean', 'PubKeyHash',
          'ValidatorHash', 'ScriptHash', 'PolicyId', 'TokenName', 'DatumHash', 'TxId'].map(t => ({
          label: t,
          kind: monaco.languages.CompletionItemKind.Class,
          insertText: t,
          range,
        })),
        // Built-in functions
        ...['sha2_256', 'sha3_256', 'blake2b_224', 'blake2b_256', 'lengthOfByteString',
          'minADA', 'contains', 'length'].map(f => ({
          label: f,
          kind: monaco.languages.CompletionItemKind.Function,
          insertText: f,
          range,
        })),
      ];

      return { suggestions };
    },
  });
}

function snippets(monaco: typeof Monaco, range: Monaco.IRange): Monaco.languages.CompletionItem[] {
  return [
    {
      label: 'Transaction( signedBy: )',
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: 'Transaction( signedBy: ${1:signer} )',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: 'Transaction signer check',
      range,
    },
    {
      label: 'Transaction( validAfter: )',
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: 'Transaction( validAfter: ${1:time} )',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: 'Time range lower bound',
      range,
    },
    {
      label: 'Datum( ... )',
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: 'Datum( ${1:DatumType}( ${2:field}: \\$${3:var} ) )',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: 'Datum pattern match',
      range,
    },
    {
      label: 'Redeemer( ... )',
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: 'Redeemer( ${1:Variant} )',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: 'Redeemer pattern match',
      range,
    },
    {
      label: 'Condition( ... )',
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: 'Condition( ${1:expression} )',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: 'Boolean condition',
      range,
    },
    {
      label: 'Output( ... )',
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: 'Output( to: ${1:address}, value: minADA( ${2:amount} ) )',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: 'Output validation',
      range,
    },
    {
      label: 'rule "..."',
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: 'rule "${1:Rule name}"\nwhen\n    ${2:pattern}\nthen ${3:allow}',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: 'New rule block',
      range,
    },
    {
      label: 'contract template',
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: 'contract "${1:MyContract}" version "1.0" purpose ${2:spending}\nparams:\n    ${3:owner} : PubKeyHash\nrule "${4:Main rule}"\nwhen\n    Transaction( signedBy: ${3:owner} )\nthen allow\ndefault: deny\n',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: 'Full contract template',
      range,
    },
  ];
}
