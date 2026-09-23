<script lang="ts">
  import { SELF, WALLETS } from './mock';

  /**
   * A credential: this script ($self), a demo wallet key, or a custom key/script hash or bech32 address.
   * Values: "$self", "key:<hex>", "script:<hex>", "addr…" / "stake…".
   */
  export let value: string;
  export let allowScript = true;
  export let allowBech32 = false;
  export let optional = false;

  $: choice = value === SELF ? SELF
    : WALLETS.some((w) => `key:${w.keyHash}` === value) ? value
    : !value ? '' : 'custom';

  function choose(e: Event) {
    const v = (e.target as HTMLSelectElement).value;
    value = v === 'custom' ? (value && choice === 'custom' ? value : 'key:') : v;
  }
</script>

<div class="credential">
  <select value={choice} on:change={choose}>
    {#if optional}<option value="">none</option>{/if}
    {#if allowScript}<option value={SELF}>this script</option>{/if}
    {#each WALLETS as w}<option value={`key:${w.keyHash}`}>{w.name} (key)</option>{/each}
    <option value="custom">custom…</option>
  </select>
  {#if choice === 'custom'}
    <input type="text" bind:value spellcheck="false"
      placeholder={allowBech32 ? 'key:<hash> · script:<hash> · addr1…' : 'key:<hash> · script:<hash>'} />
  {/if}
</div>

<style>
  .credential { display: flex; gap: 6px; align-items: center; min-width: 0; flex: 1; }
  select { font-size: 12px; padding: 3px 6px; }
  input { flex: 1; min-width: 120px; }
</style>
