<script lang="ts">
  /** Collapsible section of the mock transaction editor with a count badge and an add action. */
  export let title: string;
  export let count: number | null = null;
  export let summary = '';
  export let open = false;
  export let onAdd: (() => void) | null = null;
  export let addLabel = '+ Add';
</script>

<section class="section" class:open>
  <header>
    <button type="button" class="toggle" on:click={() => (open = !open)}>
      <span class="caret">{open ? '▾' : '▸'}</span>
      <span class="title">{title}</span>
      {#if count !== null}<span class="count" class:zero={count === 0}>{count}</span>{/if}
      {#if !open && summary}<span class="summary">{summary}</span>{/if}
    </button>
    {#if onAdd}
      <button type="button" class="add" on:click={() => { onAdd?.(); open = true; }}>{addLabel}</button>
    {/if}
  </header>
  {#if open}
    <div class="content"><slot /></div>
  {/if}
</section>

<style>
  .section { border-bottom: 1px solid var(--border); }
  header { display: flex; align-items: center; }
  .toggle { flex: 1; display: flex; align-items: center; gap: 6px; padding: 7px 4px; background: transparent; color: var(--text-primary); font-size: 12px; text-align: left; border-radius: 0; min-width: 0; }
  .toggle:hover { background: var(--bg-hover); }
  .caret { color: var(--text-muted); width: 10px; }
  .title { font-weight: 600; }
  .count { font-size: 10px; background: var(--bg-surface); color: var(--accent); padding: 0 6px; border-radius: 8px; }
  .count.zero { color: var(--text-muted); }
  .summary { color: var(--text-muted); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .add { background: transparent; color: var(--accent); font-size: 11px; padding: 3px 8px; }
  .add:hover { background: var(--bg-hover); }
  .content { padding: 2px 4px 10px 20px; display: flex; flex-direction: column; gap: 6px; }
</style>
