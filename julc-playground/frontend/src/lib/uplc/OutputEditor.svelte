<script lang="ts">
  import CredentialField from './CredentialField.svelte';
  import DataField from './DataField.svelte';
  import { ADA, SELF, credentialLabel, formatAda } from './mock';
  import type { TxOut } from './types';

  /** Editor for an output (and the resolved output of an input). */
  export let output: TxOut & { txId?: string; index?: number };
  export let showRef = false;

  let open = false;

  $: adaText = (() => {
    try {
      const l = BigInt(output.value.lovelace || '0');
      return (Number(l) / Number(ADA)).toString();
    } catch {
      return output.value.lovelace;
    }
  })();

  function setAda(e: Event) {
    const text = (e.target as HTMLInputElement).value.trim();
    const n = Number(text);
    output.value = { ...output.value, lovelace: Number.isFinite(n) ? Math.round(n * 1_000_000).toString() : output.value.lovelace };
  }

  function addAsset() {
    output.value = { ...output.value, assets: [...(output.value.assets ?? []), { policyId: SELF, tokenName: '746f6b656e', quantity: '1' }] };
  }

  function removeAsset(i: number) {
    output.value = { ...output.value, assets: (output.value.assets ?? []).filter((_, j) => j !== i) };
  }

  function setDatumKind(e: Event) {
    const kind = (e.target as HTMLSelectElement).value as 'none' | 'hash' | 'inline';
    output.datum = kind === 'none' ? { kind } : { kind, data: output.datum?.data ?? { format: 'uplc', value: 'Constr 0 []' } };
  }

  $: summary = `${credentialLabel(output.address.payment)} · ${formatAda(output.value.lovelace)}`
    + ((output.value.assets?.length ?? 0) > 0 ? ` + ${output.value.assets!.length} asset${output.value.assets!.length > 1 ? 's' : ''}` : '')
    + (output.datum && output.datum.kind !== 'none' ? ` · ${output.datum.kind} datum` : '');
</script>

<div class="output" class:open>
  <button type="button" class="summary" on:click={() => (open = !open)}>
    <span class="caret">{open ? '▾' : '▸'}</span>
    <span class="text">{summary}</span>
  </button>
  <slot name="actions" />
  {#if open}
    <div class="body">
      {#if showRef}
        <div class="row">
          <span class="key">Out ref</span>
          <input type="text" class="mono grow" bind:value={output.txId} spellcheck="false" />
          <span class="hash">#</span>
          <input type="number" class="index" bind:value={output.index} min="0" />
        </div>
      {/if}
      <div class="row">
        <span class="key">Address</span>
        <CredentialField bind:value={output.address.payment} allowBech32 />
      </div>
      <div class="row">
        <span class="key">Stake</span>
        <CredentialField bind:value={output.address.stake} optional />
      </div>
      <div class="row">
        <span class="key">Value</span>
        <input type="text" class="ada" value={adaText} on:change={setAda} />
        <span class="unit">₳</span>
        <button type="button" class="link" on:click={addAsset}>+ asset</button>
      </div>
      {#each output.value.assets ?? [] as asset, i}
        <div class="row asset">
          <span class="key"></span>
          <input type="text" class="mono" bind:value={asset.policyId} title="Policy id ($self = this script)" />
          <input type="text" class="mono" bind:value={asset.tokenName} title="Token name (hex)" />
          <input type="text" class="qty" bind:value={asset.quantity} title="Quantity" />
          <button type="button" class="remove" on:click={() => removeAsset(i)}>×</button>
        </div>
      {/each}
      <div class="row">
        <span class="key">Datum</span>
        <select value={output.datum?.kind ?? 'none'} on:change={setDatumKind}>
          <option value="none">none</option>
          <option value="inline">inline</option>
          <option value="hash">hash</option>
        </select>
      </div>
      {#if output.datum && output.datum.kind !== 'none' && output.datum.data}
        <div class="row datum"><DataField bind:value={output.datum.data} compact /></div>
      {/if}
      <div class="row">
        <span class="key">Ref script</span>
        <input type="text" class="mono grow" bind:value={output.referenceScript} placeholder="none · $self · script hash" />
      </div>
    </div>
  {/if}
</div>

<style>
  .output { border: 1px solid var(--border); border-radius: 6px; background: var(--bg-secondary); display: grid; grid-template-columns: 1fr auto; }
  .summary { display: flex; align-items: center; gap: 6px; background: transparent; color: var(--text-primary); text-align: left; padding: 5px 8px; font-size: 12px; border-radius: 6px; min-width: 0; }
  .summary .text { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .caret { color: var(--text-muted); width: 10px; }
  .body { grid-column: 1 / -1; padding: 4px 8px 8px; display: flex; flex-direction: column; gap: 5px; border-top: 1px solid var(--border); }
  .row { display: flex; align-items: center; gap: 6px; }
  .row.datum { padding-left: 70px; }
  .key { width: 64px; flex-shrink: 0; font-size: 11px; color: var(--text-muted); }
  .grow { flex: 1; }
  .mono { font-family: var(--font-mono); }
  .ada { width: 110px; }
  .index { width: 60px; }
  .qty { width: 80px; }
  .unit, .hash { font-size: 12px; color: var(--text-muted); }
  .link { background: transparent; color: var(--accent); font-size: 11px; padding: 2px 4px; }
  .remove { background: transparent; color: var(--text-muted); padding: 0 6px; font-size: 14px; }
  .remove:hover { color: var(--error); }
  input[type="text"], input[type="number"] { font-size: 12px; padding: 3px 6px; }
  select { font-size: 12px; padding: 3px 6px; }
</style>
