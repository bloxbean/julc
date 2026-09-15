<script lang="ts">
  import type { DataInput } from './types';

  /** Plutus data editor: UPLC data text, detailed-schema JSON or CBOR hex, with quick presets. */
  export let value: DataInput;
  export let label = '';
  export let placeholder = 'Constr 0 []';
  export let compact = false;

  const presets = [
    { label: 'unit', value: 'Constr 0 []' },
    { label: 'int', value: 'I 42' },
    { label: 'bytes', value: 'B #cafe' },
    { label: 'list', value: 'List [I 1, I 2]' },
  ];

  function setFormat(format: DataInput['format']) {
    value = { ...value, format };
  }

  function setText(e: Event) {
    value = { ...value, value: (e.target as HTMLTextAreaElement).value };
  }
</script>

<div class="data-field" class:compact>
  <div class="head">
    {#if label}<span class="label">{label}</span>{/if}
    <div class="formats" role="group" aria-label="Data notation">
      {#each [['auto', 'Auto'], ['uplc', 'UPLC'], ['json', 'JSON'], ['cbor', 'CBOR']] as [f, name]}
        <button type="button" class:active={value.format === f} on:click={() => setFormat(f as DataInput['format'])}>{name}</button>
      {/each}
    </div>
    {#if !compact}
      <div class="presets">
        {#each presets as p}
          <button type="button" class="chip" title={p.value} on:click={() => (value = { format: 'uplc', value: p.value })}>{p.label}</button>
        {/each}
      </div>
    {/if}
  </div>
  <textarea rows={compact ? 1 : 2} spellcheck="false" {placeholder} value={value.value} on:input={setText}></textarea>
</div>

<style>
  .data-field { display: flex; flex-direction: column; gap: 4px; }
  .head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .label { font-size: 11px; font-weight: 600; color: var(--text-secondary); min-width: 64px; }
  .formats { display: inline-flex; border: 1px solid var(--border); border-radius: 5px; overflow: hidden; }
  .formats button { padding: 1px 7px; font-size: 10px; border-radius: 0; background: transparent; color: var(--text-muted); }
  .formats button.active { background: var(--bg-surface); color: var(--accent); }
  .presets { display: inline-flex; gap: 4px; margin-left: auto; }
  .chip { padding: 1px 6px; font-size: 10px; background: var(--bg-surface); color: var(--text-secondary); border-radius: 10px; }
  textarea {
    font-family: var(--font-mono); font-size: 12px; background: var(--bg-secondary); color: var(--text-primary);
    border: 1px solid var(--border); border-radius: 4px; padding: 4px 6px; resize: vertical; width: 100%; box-sizing: border-box;
  }
  textarea:focus { outline: none; border-color: var(--accent); }
</style>
