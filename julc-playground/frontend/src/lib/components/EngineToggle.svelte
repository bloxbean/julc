<script lang="ts">
  import { engine, availableEngines, ENGINE_LABELS, wasmStatus, wasmError, type EngineKind } from '../stores/engine';

  function handleChange(event: Event) {
    engine.set((event.target as HTMLSelectElement).value as EngineKind);
  }
</script>

{#if availableEngines.length > 1}
  <label class="engine-toggle" title="Where compilation and evaluation run">
    <span class="label">Engine</span>
    <select value={$engine} on:change={handleChange}>
      {#each availableEngines as kind}
        <option value={kind}>{ENGINE_LABELS[kind]}</option>
      {/each}
    </select>
  </label>
{:else}
  <span class="engine-fixed" title="Compilation and evaluation run in this browser tab">{ENGINE_LABELS[availableEngines[0]]}</span>
{/if}
{#if $wasmStatus === 'error' && $wasmError}
  <span class="engine-error" title={$wasmError}>WebAssembly unavailable</span>
{/if}

<style>
  .engine-toggle {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .label, .engine-fixed {
    font-size: 11px;
    color: var(--text-muted);
  }

  .engine-toggle select {
    font-size: 12px;
  }

  .engine-error {
    font-size: 11px;
    color: var(--error);
  }
</style>
