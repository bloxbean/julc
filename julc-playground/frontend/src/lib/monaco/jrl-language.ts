import type * as Monaco from 'monaco-editor';

export const JRL_LANGUAGE_ID = 'jrl';

export function registerJrlLanguage(monaco: typeof Monaco) {
  monaco.languages.register({ id: JRL_LANGUAGE_ID });

  monaco.languages.setMonarchTokensProvider(JRL_LANGUAGE_ID, {
    keywords: [
      'contract', 'version', 'purpose', 'params', 'datum', 'record', 'redeemer',
      'rule', 'when', 'then', 'allow', 'deny', 'default', 'trace',
      'spending', 'minting', 'withdraw', 'certifying', 'voting', 'proposing',
    ],
    factPatterns: [
      'Redeemer', 'Transaction', 'Datum', 'Condition', 'Output',
    ],
    typeKeywords: [
      'Integer', 'Lovelace', 'POSIXTime', 'ByteString', 'Boolean', 'Data',
      'PubKeyHash', 'ValidatorHash', 'ScriptHash', 'PolicyId', 'TokenName',
      'DatumHash', 'TxId', 'List', 'Optional',
    ],
    builtinFunctions: [
      'sha2_256', 'sha3_256', 'blake2b_224', 'blake2b_256',
      'lengthOfByteString', 'sliceByteString', 'equalsByteString',
      'lessThanByteString', 'appendByteString', 'consByteString',
      'indexByteString', 'length', 'minADA', 'contains',
    ],
    transactionFields: [
      'signedBy', 'validAfter', 'validBefore',
    ],
    outputFields: [
      'to', 'value', 'datum',
    ],
    operators: [
      '==', '!=', '>=', '<=', '>', '<', '&&', '||', '!', '+', '-', '*',
    ],
    symbols: /[=><!~?:&|+\-*\/\^%]+/,

    tokenizer: {
      root: [
        // Line comments
        [/--.*$/, 'comment'],
        // Block comments
        [/\/\*/, 'comment', '@blockComment'],

        // Strings
        [/"([^"\\]|\\.)*$/, 'string.invalid'],
        [/"/, 'string', '@string'],

        // Hex literals
        [/0x[0-9a-fA-F]+/, 'number.hex'],

        // Numbers
        [/\d+/, 'number'],

        // Variable bindings ($name)
        [/\$[a-zA-Z_]\w*/, 'variable'],

        // Pipe for variant declarations
        [/\|/, 'delimiter'],

        // Identifiers
        [/[a-zA-Z_]\w*/, {
          cases: {
            '@keywords': 'keyword',
            '@factPatterns': 'type.identifier',
            '@typeKeywords': 'type',
            '@builtinFunctions': 'predefined',
            '@transactionFields': 'attribute.name',
            '@outputFields': 'attribute.name',
            '@default': 'identifier',
          },
        }],

        // Operators
        [/@symbols/, {
          cases: {
            '@operators': 'operator',
            '@default': '',
          },
        }],

        // Delimiters
        [/[{}()\[\]]/, 'delimiter.bracket'],
        [/[,;:]/, 'delimiter'],

        // Whitespace
        [/\s+/, 'white'],
      ],

      blockComment: [
        [/[^\/*]+/, 'comment'],
        [/\*\//, 'comment', '@pop'],
        [/[\/*]/, 'comment'],
      ],

      string: [
        [/[^\\"]+/, 'string'],
        [/\\./, 'string.escape'],
        [/"/, 'string', '@pop'],
      ],
    },
  });

  monaco.languages.setLanguageConfiguration(JRL_LANGUAGE_ID, {
    comments: {
      lineComment: '--',
      blockComment: ['/*', '*/'],
    },
    brackets: [
      ['(', ')'],
      ['{', '}'],
      ['[', ']'],
    ],
    autoClosingPairs: [
      { open: '(', close: ')' },
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '"', close: '"' },
    ],
    surroundingPairs: [
      { open: '(', close: ')' },
      { open: '"', close: '"' },
    ],
  });
}

export const JRL_THEME: Monaco.editor.IStandaloneThemeData = {
  base: 'vs-dark',
  inherit: true,
  rules: [
    { token: 'keyword', foreground: 'cba6f7', fontStyle: 'bold' },
    { token: 'type.identifier', foreground: '89b4fa', fontStyle: 'bold' },
    { token: 'type', foreground: 'f9e2af' },
    { token: 'predefined', foreground: '94e2d5' },
    { token: 'attribute.name', foreground: 'a6e3a1' },
    { token: 'variable', foreground: 'fab387' },
    { token: 'string', foreground: 'a6e3a1' },
    { token: 'number', foreground: 'fab387' },
    { token: 'number.hex', foreground: 'fab387' },
    { token: 'comment', foreground: '6c7086', fontStyle: 'italic' },
    { token: 'operator', foreground: '89dceb' },
    { token: 'delimiter', foreground: '9399b2' },
    { token: 'delimiter.bracket', foreground: '9399b2' },
  ],
  colors: {
    'editor.background': '#1e1e2e',
    'editor.foreground': '#cdd6f4',
    'editor.lineHighlightBackground': '#313244',
    'editorCursor.foreground': '#f5e0dc',
    'editor.selectionBackground': '#45475a',
    'editorLineNumber.foreground': '#6c7086',
    'editorLineNumber.activeForeground': '#cdd6f4',
  },
};
