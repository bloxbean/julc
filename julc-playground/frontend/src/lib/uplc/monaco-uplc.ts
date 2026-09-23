import type * as Monaco from 'monaco-editor';

let registered = false;

/** Registers UPLC syntax highlighting and the playground's dark theme (usable before the Contract editor loads). */
export function registerUplc(monaco: typeof Monaco): void {
  if (registered) return;
  registered = true;

  monaco.languages.register({ id: 'uplc' });
  monaco.languages.setMonarchTokensProvider('uplc', {
    keywords: ['program', 'lam', 'con', 'builtin', 'force', 'delay', 'error', 'constr', 'case'],
    types: ['integer', 'bytestring', 'string', 'unit', 'bool', 'data', 'list', 'pair', 'array', 'value',
      'bls12_381_G1_element', 'bls12_381_G2_element', 'bls12_381_mlresult'],
    dataKeywords: ['Constr', 'Map', 'List', 'I', 'B', 'True', 'False'],
    tokenizer: {
      root: [
        [/"([^"\\]|\\.)*"/, 'string'],
        [/#[0-9a-fA-F]*/, 'number.hex'],
        [/0x[0-9a-fA-F]+/, 'number.hex'],
        [/-?\d+\.\d+\.\d+/, 'number'],
        [/-?\d+/, 'number'],
        [/\(builtin\s+/, { token: 'keyword', next: '@builtin' }],
        [/[a-zA-Z_][\w'-]*/, {
          cases: { '@keywords': 'keyword', '@types': 'type', '@dataKeywords': 'constant', '@default': 'identifier' },
        }],
        [/[()[\]]/, '@brackets'],
      ],
      builtin: [
        [/[a-zA-Z_][\w]*/, { token: 'function', next: '@pop' }],
      ],
    },
  });
  monaco.languages.setLanguageConfiguration('uplc', {
    brackets: [['(', ')'], ['[', ']']],
    autoClosingPairs: [{ open: '(', close: ')' }, { open: '[', close: ']' }, { open: '"', close: '"' }],
  });

  monaco.editor.defineTheme('uplc-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: 'cba6f7', fontStyle: 'bold' },
      { token: 'type', foreground: 'f9e2af' },
      { token: 'function', foreground: '89b4fa' },
      { token: 'constant', foreground: 'fab387' },
      { token: 'number', foreground: 'fab387' },
      { token: 'number.hex', foreground: 'a6e3a1' },
      { token: 'string', foreground: 'a6e3a1' },
      { token: 'identifier', foreground: 'cdd6f4' },
    ],
    colors: {
      'editor.background': '#1e1e2e',
      'editor.lineHighlightBackground': '#313244',
      'editorLineNumber.foreground': '#6c7086',
      'editorGutter.background': '#1e1e2e',
    },
  });
}
