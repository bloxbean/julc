<script lang="ts">
  import './app.css';
  import Editor from './lib/components/Editor.svelte';
  import DiagnosticsPanel from './lib/components/DiagnosticsPanel.svelte';
  import PipelineView from './lib/components/PipelineView.svelte';
  import TestPanel from './lib/components/TestPanel.svelte';
  import EvalPanel from './lib/components/EvalPanel.svelte';
  import ExamplePicker from './lib/components/ExamplePicker.svelte';
  import EngineToggle from './lib/components/EngineToggle.svelte';
  import { isChecking, contractName, purpose, diagnostics } from './lib/stores/editor';
  import { engine, wasmStatus } from './lib/stores/engine';
  import { mode } from './lib/stores/mode';

  let editorRef: Editor;

  // The UPLC page (and its stores) load on first use and then stay mounted, like the Contract editor.
  let UplcPage: any = null;
  $: if ($mode === 'uplc' && !UplcPage) {
    import('./lib/uplc/UplcPage.svelte').then((m) => (UplcPage = m.default));
  }

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
      <div class="mode-tabs" role="tablist" aria-label="Playground mode">
        <button role="tab" aria-selected={$mode === 'contract'} class:active={$mode === 'contract'} on:click={() => mode.set('contract')}
          title="Write, compile and test a JuLC contract">Contract</button>
        <button role="tab" aria-selected={$mode === 'uplc'} class:active={$mode === 'uplc'} on:click={() => mode.set('uplc')}
          title="Inspect, evaluate and debug any compiled Plutus script">UPLC</button>
      </div>
      {#if $mode === 'contract' && $contractName}
        <span class="contract-info">
          {$contractName}
          {#if $purpose}
            <span class="purpose-badge">{$purpose.toLowerCase()}</span>
          {/if}
        </span>
      {/if}
    </div>
    <div class="toolbar-center">
      {#if $engine === 'wasm' && $wasmStatus === 'loading'}
        <span class="status"><span class="spinner"></span> Loading WebAssembly engine...</span>
      {:else if $mode === 'uplc'}
        <span class="status muted">Evaluate and debug any Plutus script</span>
      {:else if $isChecking}
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
      <EngineToggle />
      {#if $mode === 'contract'}
        <ExamplePicker onSelect={handleExampleSelect} />
      {/if}
    </div>
  </header>

  <!-- Main layout -->
  <div class="main" hidden={$mode !== 'contract'}>
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

  {#if UplcPage}
    <div class="uplc" hidden={$mode !== 'uplc'}>
      <svelte:component this={UplcPage} />
    </div>
  {:else if $mode === 'uplc'}
    <div class="uplc-loading"><span class="spinner"></span> Loading…</div>
  {/if}
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
  .status.muted { color: var(--text-muted); }

  .mode-tabs {
    display: inline-flex;
    background: var(--bg-primary);
    border: 1px solid var(--border);
    border-radius: 7px;
    padding: 2px;
    gap: 2px;
  }

  .mode-tabs button {
    background: transparent;
    color: var(--text-secondary);
    font-size: 12px;
    padding: 3px 12px;
    border-radius: 5px;
  }

  .mode-tabs button.active {
    background: var(--bg-surface);
    color: var(--text-primary);
    font-weight: 600;
  }

  .uplc {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }

  .uplc-loading {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    color: var(--text-muted);
  }

  [hidden] { display: none !important; }
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
