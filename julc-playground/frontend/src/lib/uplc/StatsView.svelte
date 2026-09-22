<script lang="ts">
  import { breakpoints, decodeState } from './store';

  let copied = '';

  $: info = $decodeState.response?.info ?? null;

  async function copy(what: string, text: string) {
    try {
      await navigator.clipboard.writeText(text);
      copied = what;
      setTimeout(() => (copied = ''), 1200);
    } catch {
      // clipboard unavailable
    }
  }

  function toggleBuiltin(name: string) {
    const list = $breakpoints.builtins;
    $breakpoints = { ...$breakpoints, builtins: list.includes(name) ? list.filter((b) => b !== name) : [...list, name] };
  }
</script>

<div class="stats">
  {#if !info}
    <div class="muted">{$decodeState.error ?? 'Paste a script to see its details.'}</div>
  {:else}
    <div class="cards">
      <div class="card"><span class="k">Language</span><span class="v">{info.language}</span><span class="s">{info.languageSource}</span></div>
      <div class="card"><span class="k">UPLC version</span><span class="v">{info.programVersion}</span></div>
      <div class="card"><span class="k">Size</span><span class="v">{info.flatBytes.toLocaleString()} B</span><span class="s">FLAT encoded</span></div>
      <div class="card"><span class="k">Terms</span><span class="v">{info.termCount.toLocaleString()}</span></div>
      <div class="card"><span class="k">Input</span><span class="v">{info.inputFormat}</span><span class="s">{info.wrapping}</span></div>
      <div class="card"><span class="k">Parameters</span><span class="v">{info.paramsApplied}</span><span class="s">applied</span></div>
    </div>

    <dl class="fields">
      <dt>Script hash <button type="button" class="link" on:click={() => info && copy('hash', info.scriptHash)}>{copied === 'hash' ? '✓ copied' : 'copy'}</button></dt>
      <dd class="mono">{info.scriptHash}</dd>
      {#if info.validator}<dt>Validator</dt><dd class="mono">{info.validator}</dd>{/if}
      <dt>Compiled code · double CBOR{info.paramsApplied ? ', parameters applied' : ''} <button type="button" class="link" on:click={() => info && copy('code', info.compiledCode)}>{copied === 'code' ? '✓ copied' : 'copy'}</button></dt>
      <dd class="mono code">{info.compiledCode}</dd>
      <dt>Builtins used <span class="count">{info.builtins.length}</span> <span class="muted hint">click to break on a builtin</span></dt>
      <dd class="builtins">
        {#each info.builtins as b}
          <button type="button" class="chip" class:on={$breakpoints.builtins.includes(b)} on:click={() => toggleBuiltin(b)}>{b}</button>
        {/each}
      </dd>
      {#if info.warnings.length}
        <dt>Warnings</dt>
        <dd>{#each info.warnings as w}<div class="warn">⚠ {w}</div>{/each}</dd>
      {/if}
    </dl>
  {/if}
</div>

<style>
  .stats { padding: 14px 16px; overflow: auto; height: 100%; box-sizing: border-box; font-size: 12px; }
  .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 8px; margin-bottom: 14px; }
  .card { background: var(--bg-secondary); border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; display: flex; flex-direction: column; }
  .k { font-size: 10px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--text-muted); }
  .v { font-size: 17px; font-weight: 600; color: var(--text-primary); margin-top: 2px; }
  .s { font-size: 11px; color: var(--text-muted); }
  .fields { margin: 0; }
  dt { font-size: 11px; font-weight: 600; color: var(--text-secondary); margin-top: 12px; display: flex; gap: 8px; align-items: center; }
  dd { margin: 4px 0 0; }
  .mono { font-family: var(--font-mono); word-break: break-all; color: var(--text-primary); }
  .code { max-height: 120px; overflow: auto; color: var(--text-secondary); background: var(--bg-secondary); border: 1px solid var(--border); border-radius: 6px; padding: 6px 8px; }
  .builtins { display: flex; flex-wrap: wrap; gap: 4px; }
  .chip { font-family: var(--font-mono); font-size: 11px; padding: 2px 8px; border-radius: 10px; background: var(--bg-surface); color: var(--text-secondary); }
  .chip.on { background: rgba(243, 139, 168, 0.2); color: var(--error); }
  .count { font-size: 10px; background: var(--bg-surface); color: var(--accent); padding: 0 6px; border-radius: 8px; }
  .hint { font-weight: normal; }
  .muted { color: var(--text-muted); }
  .warn { color: var(--warning); }
  .link { background: transparent; color: var(--accent); font-size: 11px; padding: 0 2px; font-weight: normal; }
</style>
