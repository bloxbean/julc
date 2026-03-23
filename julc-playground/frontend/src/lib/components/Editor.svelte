<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { source, diagnostics, isChecking, updateFromCheck } from '../stores/editor';
  import { api } from '../api/client';
  import type * as Monaco from 'monaco-editor';

  let editorContainer: HTMLDivElement;
  let editor: Monaco.editor.IStandaloneCodeEditor;
  let monacoInstance: typeof Monaco;
  let debounceTimer: ReturnType<typeof setTimeout>;
  let decorations: Monaco.editor.IEditorDecorationsCollection;

  onMount(async () => {
    monacoInstance = await import('monaco-editor');
    const { registerJrlLanguage, JRL_THEME, JRL_LANGUAGE_ID } = await import('../monaco/jrl-language');
    const { registerJrlCompletions } = await import('../monaco/jrl-completions');

    registerJrlLanguage(monacoInstance);
    registerJrlCompletions(monacoInstance);
    monacoInstance.editor.defineTheme('jrl-dark', JRL_THEME);

    editor = monacoInstance.editor.create(editorContainer, {
      value: $source,
      language: JRL_LANGUAGE_ID,
      theme: 'jrl-dark',
      fontSize: 14,
      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
      minimap: { enabled: false },
      lineNumbers: 'on',
      scrollBeyondLastLine: false,
      automaticLayout: true,
      tabSize: 4,
      renderWhitespace: 'selection',
      bracketPairColorization: { enabled: true },
      padding: { top: 8 },
    });

    decorations = editor.createDecorationsCollection([]);

    // Expose for Playwright testing
    (window as any).__jrlEditor = editor;

    editor.onDidChangeModelContent(() => {
      const value = editor.getValue();
      source.set(value);
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => checkSource(value), 300);
    });

    // Initial check
    checkSource($source);
  });

  onDestroy(() => {
    clearTimeout(debounceTimer);
    editor?.dispose();
  });

  async function checkSource(src: string) {
    if (!src.trim()) return;
    isChecking.set(true);
    try {
      const res = await api.check(src);
      updateFromCheck(res);
      updateMarkers(res.diagnostics);
    } catch {
      // Ignore network errors during typing
    } finally {
      isChecking.set(false);
    }
  }

  function updateMarkers(diags: { level: string; message: string; startLine: number | null; startCol: number | null; endLine: number | null; endCol: number | null }[]) {
    if (!monacoInstance || !editor) return;
    const model = editor.getModel();
    if (!model) return;

    const markers: Monaco.editor.IMarkerData[] = diags
      .filter(d => d.startLine != null)
      .map(d => ({
        severity: d.level === 'ERROR'
          ? monacoInstance.MarkerSeverity.Error
          : d.level === 'WARNING'
            ? monacoInstance.MarkerSeverity.Warning
            : monacoInstance.MarkerSeverity.Info,
        startLineNumber: d.startLine!,
        startColumn: d.startCol ?? 1,
        endLineNumber: d.endLine ?? d.startLine!,
        endColumn: d.endCol ?? model.getLineMaxColumn(d.endLine ?? d.startLine!),
        message: d.message,
      }));

    monacoInstance.editor.setModelMarkers(model, 'jrl', markers);
  }

  export function setValue(value: string) {
    if (editor) {
      editor.setValue(value);
    }
  }

  export function revealLine(line: number) {
    if (editor) {
      editor.revealLineInCenter(line);
      editor.setPosition({ lineNumber: line, column: 1 });
      editor.focus();
    }
  }
</script>

<div class="editor-wrapper" bind:this={editorContainer}></div>

<style>
  .editor-wrapper {
    width: 100%;
    height: 100%;
    border-radius: 8px;
    overflow: hidden;
  }
</style>
