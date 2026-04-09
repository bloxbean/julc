<script lang="ts">
  import './app.css';
  import Editor from './lib/components/Editor.svelte';
  import DiagnosticsPanel from './lib/components/DiagnosticsPanel.svelte';
  import PipelineView from './lib/components/PipelineView.svelte';
  import TestPanel from './lib/components/TestPanel.svelte';
  import EvalPanel from './lib/components/EvalPanel.svelte';
  import ExamplePicker from './lib/components/ExamplePicker.svelte';
  import { isChecking, contractName, purpose, diagnostics } from './lib/stores/editor';

  let editorRef: Editor;

  function handleExampleSelect(source: string) {
    editorRef?.setValue(source);
  }

  function handleNavigate(line: number) {
    editorRef?.revealLine(line);
  }

  $: errorCount = $diagnostics.filter(d => d.level === 'ERROR').length;
  $: warnCount = $diagnostics.filter(d => d.level === 'WARNING').length;
</script>

<div class="app">
  <!-- Toolbar -->
  <header class="toolbar">
    <div class="toolbar-left">
      <span class="logo">JuLC Playground</span>
      {#if $contractName}
        <span class="contract-info">
          {$contractName}
          {#if $purpose}
            <span class="purpose-badge">{$purpose.toLowerCase()}</span>
          {/if}
        </span>
      {/if}
    </div>
    <div class="toolbar-center">
      {#if $isChecking}
        <span class="status"><span class="spinner"></span> Checking...</span>
      {:else if errorCount > 0}
        <span class="status error">{errorCount} error{errorCount > 1 ? 's' : ''}</span>
      {:else if warnCount > 0}
        <span class="status warning">{warnCount} warning{warnCount > 1 ? 's' : ''}</span>
      {:else}
        <span class="status ok">Ready</span>
      {/if}
    </div>
    <div class="toolbar-right">
      <ExamplePicker onSelect={handleExampleSelect} />
    </div>
  </header>

  <!-- Main layout -->
  <div class="main">
    <!-- Left: Editor + Diagnostics -->
    <div class="left-panel">
      <div class="editor-area">
        <Editor bind:this={editorRef} />
      </div>
      <div class="diagnostics-area">
        <DiagnosticsPanel onNavigate={handleNavigate} />
      </div>
    </div>

    <!-- Right: Pipeline + Eval + Test -->
    <div class="right-panel">
      <div class="pipeline-area">
        <PipelineView />
      </div>
      <div class="eval-area">
        <EvalPanel />
      </div>
      <div class="test-area">
        <TestPanel />
      </div>
    </div>
  </div>
</div>

<style>
  .app {
    height: 100vh;
    display: flex;
    flex-direction: column;
    background: var(--bg-primary);
  }

  .toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 16px;
    background: var(--bg-secondary);
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
    height: 44px;
  }

  .toolbar-left {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .logo {
    font-weight: 700;
    font-size: 15px;
    color: var(--accent);
    letter-spacing: -0.3px;
  }

  .contract-info {
    font-size: 13px;
    color: var(--text-secondary);
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .purpose-badge {
    font-size: 10px;
    padding: 1px 6px;
    border-radius: 3px;
    background: var(--bg-surface);
    color: var(--accent);
    font-weight: 600;
    text-transform: uppercase;
  }

  .toolbar-center {
    position: absolute;
    left: 50%;
    transform: translateX(-50%);
  }

  .toolbar-right {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .status {
    font-size: 12px;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .status.ok { color: var(--success); }
  .status.error { color: var(--error); }
  .status.warning { color: var(--warning); }

  .main {
    flex: 1;
    display: flex;
    overflow: hidden;
  }

  .left-panel {
    flex: 1;
    display: flex;
    flex-direction: column;
    border-right: 1px solid var(--border);
    min-width: 0;
  }

  .editor-area {
    flex: 1;
    min-height: 0;
  }

  .diagnostics-area {
    height: 120px;
    border-top: 1px solid var(--border);
    overflow: auto;
    flex-shrink: 0;
  }

  .right-panel {
    width: 420px;
    display: flex;
    flex-direction: column;
    flex-shrink: 0;
  }

  .pipeline-area {
    height: 40%;
    border-bottom: 1px solid var(--border);
    overflow: hidden;
  }

  .eval-area {
    flex-shrink: 0;
    border-bottom: 1px solid var(--border);
  }

  .test-area {
    flex: 1;
    overflow: auto;
  }
</style>
