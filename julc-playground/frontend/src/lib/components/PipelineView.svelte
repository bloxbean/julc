<script lang="ts">
  import { javaSource, uplcText, compileResult, isCompiling, source } from '../stores/editor';
  import { api } from '../api/client';

  let activeTab: 'java' | 'uplc' = 'java';

  let compileError: string | null = null;

  async function compile() {
    isCompiling.set(true);
    compileError = null;
    try {
      const res = await api.compile($source);
      javaSource.set(res.javaSource);
      uplcText.set(res.uplcText);
      compileResult.set(res);
      // Show compile diagnostics in the diagnostics panel
      if (res.diagnostics && res.diagnostics.length > 0) {
        const { diagnostics } = await import('../stores/editor');
        diagnostics.set(res.diagnostics);
      }
    } catch (err: any) {
      compileError = err.message || 'Compilation failed';
      javaSource.set(null);
      uplcText.set(null);
      compileResult.set(null);
    } finally {
      isCompiling.set(false);
    }
  }
</script>

<div class="pipeline-view">
  <div class="pipeline-header">
    <div class="tabs">
      <button class:active={activeTab === 'java'} on:click={() => activeTab = 'java'}>
        Generated Java
      </button>
      <button class:active={activeTab === 'uplc'} on:click={() => activeTab = 'uplc'}>
        UPLC
      </button>
    </div>
    <div class="actions">
      {#if $compileResult}
        <span class="script-info">
          {$compileResult.scriptSizeFormatted}
        </span>
      {/if}
      <button class="primary" on:click={compile} disabled={$isCompiling}>
        {#if $isCompiling}
          <span class="spinner"></span> Compiling...
        {:else}
          Compile
        {/if}
      </button>
    </div>
  </div>

  <div class="pipeline-content">
    {#if compileError}
      <div class="compile-error">{compileError}</div>
    {/if}
    {#if activeTab === 'java'}
      <pre class="code-output">{$javaSource ?? 'Click "Compile" to generate Java code'}</pre>
    {:else}
      <pre class="code-output">{$uplcText ?? 'Click "Compile" to generate UPLC'}</pre>
    {/if}
  </div>
</div>

<style>
  .pipeline-view {
    display: flex;
    flex-direction: column;
    height: 100%;
  }

  .pipeline-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 12px;
    border-bottom: 1px solid var(--border);
    gap: 8px;
    flex-shrink: 0;
  }

  .tabs {
    display: flex;
    gap: 4px;
  }

  .tabs button {
    background: none;
    color: var(--text-secondary);
    padding: 4px 12px;
    font-size: 12px;
    border-radius: 4px;
  }

  .tabs button.active {
    background: var(--bg-surface);
    color: var(--text-primary);
  }

  .tabs button:hover:not(.active) {
    color: var(--text-primary);
  }

  .actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .script-info {
    font-size: 11px;
    color: var(--text-muted);
    font-family: var(--font-mono);
  }

  .pipeline-content {
    flex: 1;
    overflow: auto;
    padding: 8px;
  }

  .compile-error {
    color: var(--error);
    font-size: 12px;
    font-family: var(--font-mono);
    padding: 8px;
    background: rgba(243, 139, 168, 0.1);
    border-radius: 4px;
    margin-bottom: 8px;
  }

  .code-output {
    font-family: var(--font-mono);
    font-size: 12px;
    line-height: 1.5;
    color: var(--text-secondary);
    white-space: pre-wrap;
    word-break: break-all;
    margin: 0;
  }
</style>
