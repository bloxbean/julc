<script lang="ts">
  import { pirText, uplcText, blueprintJson, compileResult, isCompiling, source, librarySource, diagnostics } from '../stores/editor';
  import { api } from '../api/client';

  let activeTab: 'pir' | 'uplc' | 'compiled' | 'blueprint' = 'pir';

  let compileError: string | null = null;

  async function compile() {
    isCompiling.set(true);
    compileError = null;
    try {
      const res = await api.compile($source, $librarySource || undefined);
      pirText.set(res.pirText);
      uplcText.set(res.uplcText);
      blueprintJson.set(res.blueprintJson);
      compileResult.set(res);
      if (res.diagnostics && res.diagnostics.length > 0) {
        diagnostics.set(res.diagnostics);
      }
    } catch (err: any) {
      compileError = err.message || 'Compilation failed';
      pirText.set(null);
      uplcText.set(null);
      blueprintJson.set(null);
      compileResult.set(null);
    } finally {
      isCompiling.set(false);
    }
  }
</script>

<div class="pipeline-view">
  <div class="pipeline-header">
    <div class="tabs">
      <button class:active={activeTab === 'pir'} on:click={() => activeTab = 'pir'}>
        PIR
      </button>
      <button class:active={activeTab === 'uplc'} on:click={() => activeTab = 'uplc'}>
        UPLC
      </button>
      <button class:active={activeTab === 'compiled'} on:click={() => activeTab = 'compiled'}>
        Compiled
      </button>
      <button class:active={activeTab === 'blueprint'} on:click={() => activeTab = 'blueprint'}>
        Blueprint
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
    {#if activeTab === 'pir'}
      <pre class="code-output">{$pirText ?? 'Click "Compile" to generate PIR'}</pre>
    {:else if activeTab === 'compiled'}
      {#if $compileResult?.compiledCode}
        <div class="compiled-section">
          <div class="compiled-field">
            <span class="compiled-label">Script Hash</span>
            <pre class="compiled-value">{$compileResult.scriptHash}</pre>
          </div>
          <div class="compiled-field">
            <span class="compiled-label">Size</span>
            <pre class="compiled-value">{$compileResult.scriptSizeFormatted} ({$compileResult.scriptSizeBytes} bytes)</pre>
          </div>
          <div class="compiled-field">
            <span class="compiled-label">CBOR Hex (Double-encoded)</span>
            <pre class="compiled-value cbor-hex">{$compileResult.compiledCode}</pre>
          </div>
        </div>
      {:else}
        <pre class="code-output">Click "Compile" to generate compiled code</pre>
      {/if}
    {:else if activeTab === 'blueprint'}
      <pre class="code-output">{$blueprintJson ?? 'Click "Compile" to generate Blueprint'}</pre>
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
    flex-wrap: wrap;
  }

  .tabs button {
    background: none;
    color: var(--text-secondary);
    padding: 4px 8px;
    font-size: 11px;
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
    flex-shrink: 0;
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

  .compiled-section {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .compiled-field {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .compiled-label {
    font-size: 10px;
    font-weight: 600;
    text-transform: uppercase;
    color: var(--text-muted);
    letter-spacing: 0.5px;
  }

  .compiled-value {
    font-family: var(--font-mono);
    font-size: 12px;
    color: var(--text-secondary);
    margin: 0;
    padding: 6px 8px;
    background: var(--bg-primary);
    border-radius: 4px;
    word-break: break-all;
    white-space: pre-wrap;
    user-select: all;
  }

  .cbor-hex {
    font-size: 11px;
    line-height: 1.4;
  }
</style>
