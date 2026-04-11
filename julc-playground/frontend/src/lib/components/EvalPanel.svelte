<script lang="ts">
  import { api, type EvalExpressionResponse } from '../api/client';
  import BudgetMeter from './BudgetMeter.svelte';

  let expression = '';
  let isEvaluating = false;
  let result: EvalExpressionResponse | null = null;
  let collapsed = false;
  let showUplc = false;

  async function evaluate() {
    if (!expression.trim()) return;
    isEvaluating = true;
    result = null;
    try {
      result = await api.evalExpression(expression);
    } catch (err: any) {
      result = {
        success: false, result: null, type: null,
        budgetCpu: 0, budgetMem: 0, traces: [],
        error: err.message, uplc: null
      };
    } finally {
      isEvaluating = false;
    }
  }

  function handleKeydown(e: KeyboardEvent) {
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault();
      evaluate();
    }
  }
</script>

<div class="eval-panel">
  <button class="section-toggle" on:click={() => collapsed = !collapsed}>
    <span class="toggle-icon">{collapsed ? '\u25B6' : '\u25BC'}</span>
    Quick Eval
  </button>

  {#if !collapsed}
    <div class="eval-content">
      <div class="eval-input-row">
        <input
          type="text"
          placeholder="Expression (e.g. 1 + 2, MathLib.pow(2, 10))"
          bind:value={expression}
          on:keydown={handleKeydown}
          class="eval-input"
        />
        <button class="primary small" on:click={evaluate} disabled={isEvaluating}>
          {isEvaluating ? '...' : 'Eval'}
        </button>
      </div>

      {#if result}
        <div class="eval-result">
          {#if result.success}
            <div class="result-value">
              <span class="result-label">= </span>
              <span class="result-text">{result.result}</span>
              {#if result.type}
                <span class="result-type">{result.type}</span>
              {/if}
            </div>
          {:else}
            <div class="result-error">{result.error}</div>
          {/if}

          {#if result.budgetCpu > 0 || result.budgetMem > 0}
            <div class="result-budget">
              <span>{result.budgetCpu.toLocaleString()} CPU</span>
              <span>{result.budgetMem.toLocaleString()} Mem</span>
            </div>
          {/if}

          {#if result.traces.length > 0}
            <div class="result-traces">
              {#each result.traces as trace}
                <div class="trace-line">{trace}</div>
              {/each}
            </div>
          {/if}

          {#if result.uplc}
            <button class="secondary small" on:click={() => showUplc = !showUplc}>
              {showUplc ? 'Hide' : 'Show'} UPLC
            </button>
            {#if showUplc}
              <pre class="uplc-output">{result.uplc}</pre>
            {/if}
          {/if}
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .eval-panel {
    border-top: 1px solid var(--border);
    padding: 0;
  }

  .section-toggle {
    width: 100%;
    background: none;
    border: none;
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    padding: 8px 12px;
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 6px;
    text-align: left;
  }

  .section-toggle:hover {
    color: var(--text-primary);
  }

  .toggle-icon {
    font-size: 9px;
  }

  .eval-content {
    padding: 0 12px 12px;
  }

  .eval-input-row {
    display: flex;
    gap: 6px;
  }

  .eval-input {
    flex: 1;
    font-family: var(--font-mono);
    font-size: 12px;
  }

  .small {
    padding: 4px 10px;
    font-size: 12px;
  }

  .eval-result {
    margin-top: 8px;
  }

  .result-value {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-family: var(--font-mono);
    font-size: 13px;
  }

  .result-label {
    color: var(--text-muted);
  }

  .result-text {
    color: var(--success);
    font-weight: 600;
  }

  .result-type {
    font-size: 10px;
    color: var(--text-muted);
    background: var(--bg-surface);
    padding: 1px 5px;
    border-radius: 3px;
  }

  .result-error {
    color: var(--error);
    font-size: 12px;
    font-family: var(--font-mono);
    padding: 4px 6px;
    background: rgba(243, 139, 168, 0.1);
    border-radius: 4px;
    word-break: break-word;
  }

  .result-budget {
    font-size: 10px;
    color: var(--text-muted);
    font-family: var(--font-mono);
    margin-top: 4px;
    display: flex;
    gap: 12px;
  }

  .result-traces {
    margin-top: 4px;
  }

  .trace-line {
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--text-secondary);
  }

  .uplc-output {
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--text-secondary);
    white-space: pre-wrap;
    word-break: break-all;
    margin: 4px 0 0;
    max-height: 100px;
    overflow: auto;
    background: var(--bg-primary);
    padding: 4px;
    border-radius: 4px;
  }
</style>
