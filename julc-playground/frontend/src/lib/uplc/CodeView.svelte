<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import type * as Monaco from 'monaco-editor';
  import { registerUplc } from './monaco-uplc';
  import type { Span } from './types';

  /** Read-only Monaco view with highlight spans and optional gutter breakpoints. */
  export let text = '';
  export let language: 'uplc' | 'java' = 'uplc';
  export let currentSpan: Span | null = null;
  export let failedSpan: Span | null = null;
  export let peekSpan: Span | null = null;
  export let breakpointLines: number[] = [];
  export let onToggleBreakpoint: ((line: number) => void) | null = null;

  let container: HTMLDivElement;
  let monaco: typeof Monaco;
  let editor: Monaco.editor.IStandaloneCodeEditor | undefined;
  let decorations: Monaco.editor.IEditorDecorationsCollection | undefined;

  onMount(async () => {
    monaco = await import('monaco-editor');
    registerUplc(monaco);
    editor = monaco.editor.create(container, {
      value: text,
      language,
      theme: 'uplc-dark',
      readOnly: true,
      domReadOnly: true,
      fontSize: 13,
      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
      minimap: { enabled: text.length > 4000 },
      glyphMargin: onToggleBreakpoint != null,
      folding: true,
      stickyScroll: { enabled: false },
      lineNumbersMinChars: 4,
      scrollBeyondLastLine: false,
      automaticLayout: true,
      renderLineHighlight: 'none',
      wordWrap: 'off',
      padding: { top: 6 },
    });
    decorations = editor.createDecorationsCollection([]);
    editor.onMouseDown((e) => {
      if (!onToggleBreakpoint || !e.target.position) return;
      const t = e.target.type;
      if (t === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN || t === monaco.editor.MouseTargetType.GUTTER_LINE_NUMBERS) {
        onToggleBreakpoint(e.target.position.lineNumber);
      }
    });
    updateDecorations();
  });

  onDestroy(() => editor?.dispose());

  $: if (editor && editor.getValue() !== text) {
    editor.setValue(text);
    editor.updateOptions({ minimap: { enabled: text.length > 4000 } });
  }
  $: if (editor && monaco) monaco.editor.setModelLanguage(editor.getModel()!, language);
  $: currentSpan, failedSpan, peekSpan, breakpointLines, updateDecorations();
  $: if (editor && currentSpan) reveal(currentSpan);
  $: if (editor && peekSpan) reveal(peekSpan);
  $: if (editor && failedSpan && !currentSpan) reveal(failedSpan);

  function range(span: Span) {
    return new monaco.Range(span.startLine, span.startColumn, span.endLine, span.endColumn);
  }

  function reveal(span: Span) {
    editor?.revealRangeInCenterIfOutsideViewport(range(span), monaco.editor.ScrollType.Smooth);
  }

  function updateDecorations() {
    if (!editor || !monaco || !decorations) return;
    const list: Monaco.editor.IModelDeltaDecoration[] = [];
    for (const line of breakpointLines) {
      list.push({ range: new monaco.Range(line, 1, line, 1), options: { glyphMarginClassName: 'uplc-breakpoint', stickiness: 1 } });
    }
    if (failedSpan) {
      highlight(list, failedSpan, 'uplc-failed');
      list.push({ range: new monaco.Range(failedSpan.startLine, 1, failedSpan.startLine, 1), options: { isWholeLine: true, className: 'uplc-failed-line' } });
    }
    if (currentSpan) {
      highlight(list, currentSpan, 'uplc-current');
      list.push({ range: new monaco.Range(currentSpan.startLine, 1, currentSpan.startLine, 1), options: { isWholeLine: true, className: 'uplc-current-line', glyphMarginClassName: 'uplc-current-glyph' } });
    }
    if (peekSpan) {
      highlight(list, peekSpan, 'uplc-peek');
    }
    decorations.set(list);
  }

  /** Strong highlight on a term's first line; the rest of a multi-line term only gets a faint tint. */
  function highlight(list: Monaco.editor.IModelDeltaDecoration[], span: Span, css: string) {
    if (span.startLine === span.endLine) {
      list.push({ range: range(span), options: { inlineClassName: `${css}-term` } });
      return;
    }
    const model = editor!.getModel()!;
    list.push({ range: new monaco.Range(span.startLine, span.startColumn, span.startLine, model.getLineMaxColumn(span.startLine)), options: { inlineClassName: `${css}-term` } });
    list.push({ range: new monaco.Range(span.startLine + 1, 1, span.endLine, span.endColumn), options: { className: `${css}-rest` } });
  }
</script>

<div class="code-view" bind:this={container}></div>

<style>
  .code-view { width: 100%; height: 100%; }
  :global(.uplc-breakpoint) { background: var(--error); border-radius: 50%; width: 10px !important; height: 10px !important; margin: 4px 0 0 6px; }
  :global(.uplc-current-glyph)::after { content: '▶'; color: var(--warning); font-size: 11px; margin-left: 5px; }
  :global(.uplc-current-line) { background: rgba(250, 179, 135, 0.08); }
  :global(.uplc-current-term) { background: rgba(250, 179, 135, 0.28); border-radius: 2px; }
  :global(.uplc-failed-line) { background: rgba(243, 139, 168, 0.1); }
  :global(.uplc-failed-term) { background: rgba(243, 139, 168, 0.35); border-radius: 2px; }
  :global(.uplc-peek-term) { outline: 1px dashed var(--accent); }
  :global(.uplc-current-rest) { background: rgba(250, 179, 135, 0.05); }
  :global(.uplc-failed-rest) { background: rgba(243, 139, 168, 0.06); }
  :global(.uplc-peek-rest) { background: rgba(137, 180, 250, 0.06); }
</style>
