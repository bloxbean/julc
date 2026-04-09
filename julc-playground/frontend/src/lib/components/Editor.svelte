<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { source, diagnostics, isChecking, updateFromCheck, librarySource, editorTab } from '../stores/editor';
  import { api } from '../api/client';
  import type * as Monaco from 'monaco-editor';

  let editorContainer: HTMLDivElement;
  let editor: Monaco.editor.IStandaloneCodeEditor;
  let monacoInstance: typeof Monaco;
  let debounceTimer: ReturnType<typeof setTimeout>;
  let decorations: Monaco.editor.IEditorDecorationsCollection;
  let checkAbort: AbortController | null = null;
  let unsubs: (() => void)[] = [];

  onMount(async () => {
    monacoInstance = await import('monaco-editor');
    const jrlLang = await import('../monaco/jrl-language');
    const { registerJavaCompletions, registerJavaHoverProvider, registerJavaSignatureHelpProvider, registerJavaDefinitionProvider, setLibrarySource: setLibSrc } = await import('../monaco/java-completions');

    jrlLang.registerJrlLanguage(monacoInstance);
    registerJavaCompletions(monacoInstance);
    registerJavaHoverProvider(monacoInstance);
    registerJavaSignatureHelpProvider(monacoInstance);
    registerJavaDefinitionProvider(monacoInstance);
    monacoInstance.editor.defineTheme('jrl-dark', jrlLang.JRL_THEME);

    editor = monacoInstance.editor.create(editorContainer, {
      value: $source,
      language: 'java',
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
      if ($editorTab === 'library') {
        librarySource.set(value);
      } else {
        source.set(value);
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => checkSource(value), 300);
      }
    });

    // React to source changes from outside (e.g., example load)
    const unsubSource = source.subscribe(val => {
      if (editor && $editorTab === 'validator' && editor.getValue() !== val) {
        editor.setValue(val);
      }
    });

    // React to editor tab changes (Validator / Library)
    let lastTab = $editorTab;
    const unsubTab = editorTab.subscribe(tab => {
      if (!editor || tab === lastTab) return;
      lastTab = tab;
      if (tab === 'library') {
        // Save current validator source, load library source
        source.set(editor.getValue());
        editor.setValue($librarySource);
      } else {
        // Save current library source, load validator source
        librarySource.set(editor.getValue());
        editor.setValue($source);
      }
    });

    // Wire library source into autocomplete engine
    const unsubLib = librarySource.subscribe(val => {
      setLibSrc(val);
    });

    unsubs = [unsubSource, unsubTab, unsubLib];

    // Initial check
    checkSource($source);
  });

  onDestroy(() => {
    unsubs.forEach(fn => fn());
    clearTimeout(debounceTimer);
    editor?.dispose();
  });

  async function checkSource(src: string) {
    if (!src.trim()) return;
    // Cancel previous in-flight check
    checkAbort?.abort();
    checkAbort = new AbortController();
    isChecking.set(true);
    try {
      const res = await api.check(src, checkAbort.signal);
      updateFromCheck(res);
      updateMarkers(res.diagnostics);
    } catch (e: unknown) {
      // Ignore aborted requests and network errors during typing
      if (e instanceof DOMException && e.name === 'AbortError') return;
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

    monacoInstance.editor.setModelMarkers(model, 'julc', markers);
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

<div class="editor-container">
  <div class="editor-tabs">
    <button class:active={$editorTab === 'validator'} on:click={() => editorTab.set('validator')}>
      Validator
    </button>
    <button class:active={$editorTab === 'library'} on:click={() => editorTab.set('library')}>
      Library {$librarySource ? '*' : ''}
    </button>
  </div>
  <div class="editor-wrapper" bind:this={editorContainer}></div>
</div>

<style>
  .editor-container {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
  }

  .editor-tabs {
    display: flex;
    gap: 2px;
    padding: 4px 8px;
    background: var(--bg-secondary);
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
  }

  .editor-tabs button {
    background: none;
    color: var(--text-secondary);
    padding: 3px 10px;
    font-size: 12px;
    font-weight: 500;
    border-radius: 4px;
    cursor: pointer;
    border: none;
  }

  .editor-tabs button.active {
    background: var(--bg-surface);
    color: var(--text-primary);
  }

  .editor-tabs button:hover:not(.active) {
    color: var(--text-primary);
  }

  .editor-wrapper {
    width: 100%;
    flex: 1;
    min-height: 0;
    border-radius: 8px;
    overflow: hidden;
  }
</style>
