<script lang="ts">
  import { PRESETS, formatAda, formatNumber, scriptFee } from './mock';
  import { evalState, evaluate, peekSpan, preset, presetIndex, startDebug } from './store';

  $: r = $evalState.response;
  $: cpuPct = r ? Math.min(100, (r.cpu / $preset.maxCpu) * 100) : 0;
  $: memPct = r ? Math.min(100, (r.mem / $preset.maxMem) * 100) : 0;
  $: fee = r ? scriptFee($preset, r.cpu, r.mem) : 0;
  $: tone = !r ? '' : r.status === 'success' && r.accepted ? 'ok' : r.status === 'success' ? 'warn' : 'bad';
  $: headline = !r ? '' : r.status === 'success' && r.accepted ? 'Script accepted'
    : r.status === 'success' ? 'Script rejected'
    : r.status === 'budgetExhausted' ? 'Budget exhausted' : 'Script failed';

  const color = (pct: number) => (pct < 50 ? 'var(--success)' : pct < 80 ? 'var(--warning)' : 'var(--error)');
  const pctText = (pct: number) => (pct === 0 ? '0%' : pct < 0.01 ? '<0.01%' : `${pct < 1 ? pct.toFixed(2) : pct.toFixed(1)}%`);
</script>

<div class="result">
  {#if $evalState.error}
    <div class="banner bad"><span class="icon">✕</span><div><strong>Could not evaluate</strong><div class="detail">{$evalState.error}</div></div></div>
  {:else if !r}
    <div class="empty">
      {#if $evalState.loading}<span class="spinner"></span> Evaluating…
      {:else}Press <kbd>Run</kbd> (<kbd>⌘</kbd><kbd>⏎</kbd>) to evaluate the script against the mock transaction, or <kbd>Debug</kbd> (<kbd>F5</kbd>) to step through it.{/if}
    </div>
  {:else}
    <div class="banner {tone}" class:stale={$evalState.stale}>
      <span class="icon">{tone === 'ok' ? '✓' : tone === 'warn' ? '!' : '✕'}</span>
      <div class="grow">
        <strong>{headline}</strong>
        {#if r.message}<div class="detail">{r.message}</div>{/if}
        {#if $evalState.stale}<div class="detail muted">Inputs changed since this run. <button type="button" class="link" on:click={evaluate}>Run again</button></div>{/if}
      </div>
      {#if r.status !== 'success'}
        <div class="banner-actions">
          {#if r.failedSpan}<button type="button" class="secondary small" on:click={() => peekSpan.set(r?.failedSpan ?? null)}>Show term</button>{/if}
          <button type="button" class="primary small" on:click={() => startDebug(true)}>Debug from failure</button>
        </div>
      {/if}
    </div>

    <div class="grid">
      <div class="card budget">
        <div class="card-title">
          Execution budget
          <select bind:value={$presetIndex} title="Protocol parameters for limits and prices">
            {#each PRESETS as p, i}<option value={i}>{p.name}</option>{/each}
          </select>
        </div>
        {#each [['CPU', r.cpu, $preset.maxCpu, cpuPct], ['Memory', r.mem, $preset.maxMem, memPct]] as [label, used, max, pct]}
          <div class="meter">
            <span class="meter-label">{label}</span>
            <div class="bar"><div class="fill" style="width: {Math.max(Number(pct), 0.5)}%; background: {color(Number(pct))}"></div></div>
            <span class="meter-value" title={`${Number(used).toLocaleString()} units`}>{formatNumber(Number(used))} / {formatNumber(Number(max))}</span>
            <span class="meter-pct">{pctText(Number(pct))}</span>
          </div>
        {/each}
        <div class="fee">
          Script execution fee <strong>{formatAda(String(fee))}</strong>
          <span class="muted">({fee.toLocaleString()} lovelace · {$preset.priceMem} × mem + {$preset.priceSteps} × cpu)</span>
        </div>
      </div>

      <div class="card">
        <div class="card-title">Traces <span class="count">{r.traces.length}</span></div>
        {#if r.traces.length}
          <ol class="traces">{#each r.traces as t}<li>{t}</li>{/each}</ol>
        {:else}<div class="muted small-text">No traces emitted</div>{/if}
      </div>

      {#if r.result}
        <div class="card">
          <div class="card-title">Result</div>
          <pre class="code">{r.result}</pre>
        </div>
      {/if}

      {#if r.lastBuiltins.length}
        <div class="card">
          <div class="card-title">Last builtin calls <span class="count">{r.lastBuiltins.length}</span></div>
          <ol class="builtins">{#each r.lastBuiltins as b}<li>{b}</li>{/each}</ol>
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .result { padding: 10px 14px; display: flex; flex-direction: column; gap: 10px; }
  .empty { color: var(--text-muted); font-size: 12px; padding: 18px 4px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
  kbd { font-family: var(--font-mono); font-size: 10px; background: var(--bg-surface); border: 1px solid var(--border); border-bottom-width: 2px; border-radius: 4px; padding: 0 4px; }
  .banner { display: flex; gap: 10px; align-items: flex-start; padding: 9px 12px; border-radius: 8px; font-size: 13px; border: 1px solid transparent; }
  .banner.ok { background: rgba(166, 227, 161, 0.1); border-color: rgba(166, 227, 161, 0.35); }
  .banner.warn { background: rgba(250, 179, 135, 0.1); border-color: rgba(250, 179, 135, 0.35); }
  .banner.bad { background: rgba(243, 139, 168, 0.1); border-color: rgba(243, 139, 168, 0.35); }
  .banner.stale { opacity: 0.7; }
  .icon { width: 20px; height: 20px; border-radius: 50%; display: grid; place-items: center; font-size: 12px; font-weight: 700; flex-shrink: 0; color: var(--bg-primary); }
  .ok .icon { background: var(--success); }
  .warn .icon { background: var(--warning); }
  .bad .icon { background: var(--error); }
  .detail { font-size: 12px; color: var(--text-secondary); margin-top: 2px; font-family: var(--font-mono); white-space: pre-wrap; word-break: break-word; }
  .grow { flex: 1; min-width: 0; }
  .banner-actions { display: flex; gap: 6px; flex-shrink: 0; }
  button.small { font-size: 11px; padding: 4px 10px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 10px; }
  .card { background: var(--bg-secondary); border: 1px solid var(--border); border-radius: 8px; padding: 9px 12px; min-width: 0; }
  .card.budget { grid-column: 1 / -1; }
  .card-title { font-size: 11px; font-weight: 600; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.4px; display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
  .card-title select { margin-left: auto; font-size: 11px; padding: 1px 6px; text-transform: none; }
  .count { font-size: 10px; background: var(--bg-surface); color: var(--accent); padding: 0 6px; border-radius: 8px; }
  .meter { display: flex; align-items: center; gap: 10px; margin: 5px 0; }
  .meter-label { width: 52px; font-size: 11px; color: var(--text-secondary); }
  .bar { flex: 1; height: 8px; background: var(--bg-primary); border-radius: 4px; overflow: hidden; }
  .fill { height: 100%; border-radius: 4px; transition: width 0.3s ease; }
  .meter-value { font-family: var(--font-mono); font-size: 11px; color: var(--text-primary); min-width: 110px; text-align: right; }
  .meter-pct { font-family: var(--font-mono); font-size: 11px; color: var(--text-muted); min-width: 52px; text-align: right; }
  .fee { font-size: 12px; color: var(--text-secondary); margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; align-items: baseline; }
  .fee strong { color: var(--text-primary); }
  .muted { color: var(--text-muted); }
  .small-text { font-size: 12px; }
  .traces, .builtins { margin: 0; padding-left: 22px; font-family: var(--font-mono); font-size: 12px; max-height: 160px; overflow: auto; }
  .traces li { color: var(--warning); padding: 1px 0; }
  .builtins li { color: var(--text-secondary); padding: 1px 0; }
  .code { margin: 0; font-family: var(--font-mono); font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 160px; overflow: auto; color: var(--text-primary); }
  .link { background: transparent; color: var(--accent); font-size: 11px; padding: 0 2px; }
</style>
