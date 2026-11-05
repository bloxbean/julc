import type * as Monaco from 'monaco-editor';

/** The editor's dark theme. */
export const JULC_THEME: Monaco.editor.IStandaloneThemeData = {
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
