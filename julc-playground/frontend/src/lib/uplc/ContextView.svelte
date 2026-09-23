<script lang="ts">
  import { evalState } from './store';

  let copied = '';

  async function copy(what: string, text: string | null) {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      copied = what;
      setTimeout(() => (copied = ''), 1200);
    } catch {
      // clipboard unavailable
    }
  }

  $: r = $evalState.response;
</script>

<div class="context">
  {#if !r?.scriptContext}
    <div class="empty">Run the script to see the exact ScriptContext (and arguments) passed to it.</div>
  {:else}
    <div class="head">
      <span>Script context passed to the {r.language ?? ''} script{r.scriptHash ? ` ${r.scriptHash.slice(0, 10)}…` : ''}</span>
      <button type="button" class="secondary small" on:click={() => copy('data', r?.scriptContext ?? null)}>{copied === 'data' ? '✓ Copied' : 'Copy data'}</button>
      <button type="button" class="secondary small" on:click={() => copy('cbor', r?.scriptContextCbor ?? null)}>{copied === 'cbor' ? '✓ Copied' : 'Copy CBOR'}</button>
    </div>
    <pre class="code">{r.scriptContext}</pre>
    {#if r.scriptContextCbor}
      <div class="head"><span>CBOR · {(r.scriptContextCbor.length / 2).toLocaleString()} bytes</span></div>
      <pre class="code cbor">{r.scriptContextCbor}</pre>
    {/if}
  {/if}
</div>

<style>
  .context { padding: 10px 14px; display: flex; flex-direction: column; gap: 8px; }
  .empty { color: var(--text-muted); font-size: 12px; padding: 18px 4px; }
  .head { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-secondary); }
  .head span { flex: 1; }
  button.small { font-size: 11px; padding: 3px 10px; }
  .code { margin: 0; font-family: var(--font-mono); font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; background: var(--bg-secondary); border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; color: var(--text-primary); }
  .cbor { color: var(--text-secondary); max-height: 120px; overflow: auto; }
</style>
