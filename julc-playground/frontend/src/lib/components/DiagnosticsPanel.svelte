<script lang="ts">
  import { diagnostics } from '../stores/editor';

  export let onNavigate: (line: number) => void = () => {};

  function levelClass(level: string): string {
    switch (level) {
      case 'ERROR': return 'error';
      case 'WARNING': return 'warning';
      default: return 'info';
    }
  }
</script>

<div class="diagnostics-panel">
  {#if $diagnostics.length === 0}
    <div class="empty">No issues found</div>
  {:else}
    {#each $diagnostics as diag}
      <div
        class="diagnostic-item {levelClass(diag.level)}"
        on:click={() => diag.startLine && onNavigate(diag.startLine)}
        on:keydown={(e) => e.key === 'Enter' && diag.startLine && onNavigate(diag.startLine)}
        role="button"
        tabindex="0"
      >
        <span class="badge {levelClass(diag.level)}">{diag.level}</span>
        <span class="code">{diag.code}</span>
        <span class="message">{diag.message}</span>
        {#if diag.startLine}
          <span class="location">Ln {diag.startLine}{diag.startCol ? `:${diag.startCol}` : ''}</span>
        {/if}
        {#if diag.suggestion}
          <div class="suggestion">{diag.suggestion}</div>
        {/if}
      </div>
    {/each}
  {/if}
</div>

<style>
  .diagnostics-panel {
    padding: 8px;
    overflow-y: auto;
    max-height: 100%;
    font-size: 12px;
  }

  .empty {
    color: var(--text-muted);
    padding: 12px;
    text-align: center;
  }

  .diagnostic-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 8px;
    border-radius: 4px;
    cursor: pointer;
    flex-wrap: wrap;
  }

  .diagnostic-item:hover {
    background: var(--bg-hover);
  }

  .code {
    color: var(--text-muted);
    font-family: var(--font-mono);
    font-size: 11px;
  }

  .message {
    flex: 1;
    color: var(--text-primary);
  }

  .location {
    color: var(--text-muted);
    font-family: var(--font-mono);
    font-size: 11px;
  }

  .suggestion {
    width: 100%;
    color: var(--text-secondary);
    font-size: 11px;
    padding-left: 60px;
    margin-top: 2px;
  }
</style>
