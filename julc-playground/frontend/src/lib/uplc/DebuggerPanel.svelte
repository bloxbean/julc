<script lang="ts">
  import { formatNumber } from './mock';
  import {
    breakpoints, debugBack, debugContinue, debugGoto, debugOut, debugOver, debugRestart, debugState, debugStep,
    decodeState, peekSpan, startDebug, stopDebug,
  } from './store';
  import type { Span } from './types';

  let envFilter = '';
  let scrubValue: number | null = null;
  let builtinInput = '';
  let expanded = '';

  $: s = $debugState.snapshot;
  $: t = $debugState.timeline;
  $: total = t?.totalSteps ?? 0;
  $: step = s?.step ?? 0;
  $: finished = s?.finished ?? false;
  $: busy = $debugState.loading;
  $: env = (s?.environment ?? []).filter((e) => !envFilter || e.name.toLowerCase().includes(envFilter.toLowerCase()) || e.value.toLowerCase().includes(envFilter.toLowerCase()));
  $: knownBuiltins = $decodeState.response?.info?.builtins ?? [];
  $: pct = (n: number) => (total > 0 ? (n / total) * 100 : 0);

  const REASONS: Record<string, string> = {
    step: 'Paused', breakpoint: 'Breakpoint', trace: 'Trace emitted', builtin: 'Builtin breakpoint', end: 'Finished', error: 'Stopped at error', limit: 'Step limit reached',
  };
  const PHASES: Record<string, string> = {
    compute: 'Computing term', return: 'Returning value', done: 'Evaluation finished', failed: 'Evaluation failed',
  };

  function scrubInput(e: Event) {
    scrubValue = Number((e.target as HTMLInputElement).value);
  }
  function scrubCommit() {
    if (scrubValue !== null && scrubValue !== step) debugGoto(scrubValue);
    scrubValue = null;
  }

  function peek(span: Span | null) {
    if (!span) return;
    peekSpan.set(span);
    setTimeout(() => peekSpan.update((p) => (p === span ? null : p)), 2500);
  }

  function addBuiltin() {
    const name = builtinInput.trim();
    if (name && !$breakpoints.builtins.includes(name)) $breakpoints = { ...$breakpoints, builtins: [...$breakpoints.builtins, name] };
    builtinInput = '';
  }
</script>

<div class="debugger">
  {#if !$debugState.active}
    <div class="empty">
      {#if busy}
        <span class="spinner"></span> Running the script to build the timeline…
      {:else}
        <div>
          <p>Step through the CEK machine one transition at a time. The current term is highlighted in the UPLC view, with its environment, continuation frames and budget.</p>
          <p class="muted">Click the gutter of the UPLC view to add line breakpoints. Traces and the failure point appear as markers on the timeline.</p>
          {#if $debugState.error}<p class="error">{$debugState.error}</p>{/if}
          <button type="button" class="primary" on:click={() => startDebug()}>Start debugging <kbd>F5</kbd></button>
        </div>
      {/if}
    </div>
  {:else}
    <div class="controls">
      <div class="buttons">
        <button type="button" title="Restart" on:click={debugRestart} disabled={busy || step === 0}>⟲</button>
        <button type="button" title="Step back (⇧F10)" on:click={debugBack} disabled={busy || step === 0}>◀</button>
        <button type="button" title="Step into (F11)" class="main" on:click={debugStep} disabled={busy || finished}>Step</button>
        <button type="button" title="Step over (F10): finish the current term" on:click={debugOver} disabled={busy || finished}>Over</button>
        <button type="button" title="Step out (⇧F11): return to the enclosing frame" on:click={debugOut} disabled={busy || finished}>Out</button>
        <button type="button" title="Continue to the next breakpoint (F8)" on:click={debugContinue} disabled={busy || finished}>Continue ▸▸</button>
        <button type="button" title="Stop debugging" class="stop" on:click={stopDebug}>■</button>
      </div>
      <div class="position">
        {#if busy}<span class="spinner"></span>{/if}
        <span class="reason {s?.stopReason}">{REASONS[s?.stopReason ?? 'step'] ?? s?.stopReason}</span>
        <span class="mono">step {(scrubValue ?? step).toLocaleString()} / {total.toLocaleString()}{t?.truncated ? '+' : ''}</span>
      </div>
    </div>

    {#if $debugState.stale}
      <div class="stale">Script or transaction changed. <button type="button" class="link" on:click={() => startDebug()}>Restart with the new inputs</button></div>
    {/if}
    {#if $debugState.error}<div class="stale error">{$debugState.error}</div>{/if}

    <div class="scrubber">
      <div class="markers">
        {#each t?.traceSteps ?? [] as ts}
          <button type="button" class="marker trace" style="left: {pct(ts)}%" title={`Trace at step ${ts.toLocaleString()}`} on:click={() => debugGoto(ts)}></button>
        {/each}
        {#if t?.errorStep != null}
          <button type="button" class="marker error" style="left: {pct(t.errorStep)}%" title={`Failure at step ${t.errorStep.toLocaleString()}`} on:click={() => t?.errorStep != null && debugGoto(t.errorStep)}></button>
        {/if}
      </div>
      <input type="range" min="0" max={total} value={scrubValue ?? step} on:input={scrubInput} on:change={scrubCommit} aria-label="Evaluation step" />
    </div>

    <div class="panes">
      <section class="pane state">
        <h4>State</h4>
        <div class="phase {s?.phase}">{PHASES[s?.phase ?? 'compute']}{s?.termKind && s.phase === 'compute' ? ` · ${s.termKind}` : ''}</div>
        {#if s?.error}<div class="error-text">{s.error}</div>{/if}
        {#if s?.value}
          <div class="label">Value</div>
          <pre class="value">{s.value}</pre>
        {/if}
        <div class="label">Budget</div>
        <div class="budget">
          <span>CPU <strong>{formatNumber(s?.cpu ?? 0)}</strong>{#if s?.cpuDelta}<em>+{s.cpuDelta.toLocaleString()}</em>{/if}</span>
          <span>Mem <strong>{formatNumber(s?.mem ?? 0)}</strong>{#if s?.memDelta}<em>+{s.memDelta.toLocaleString()}</em>{/if}</span>
        </div>
        <div class="label">Stack depth <span class="mono">{s?.stackDepth ?? 0}</span></div>
      </section>

      <section class="pane">
        <h4>Environment <span class="count">{s?.environment.length ?? 0}</span>
          <input type="text" class="filter" placeholder="filter" bind:value={envFilter} />
        </h4>
        {#if env.length}
          <dl class="env">
            {#each env as e}
              <dt class="mono">{e.name}</dt>
              <dd><button type="button" class="mono env-value" class:expanded={expanded === e.name} title={expanded === e.name ? '' : 'Click to expand'}
                on:click={() => (expanded = expanded === e.name ? '' : e.name)}>{e.value}</button></dd>
            {/each}
          </dl>
        {:else}
          <div class="muted small">{s?.phase === 'return' ? 'Returning a value: the environment belongs to the frame that receives it.' : 'Empty environment'}</div>
        {/if}
      </section>

      <section class="pane">
        <h4>Frames <span class="count">{s?.stackDepth ?? 0}</span></h4>
        {#if s?.frames.length}
          <ol class="frames">
            {#each s.frames as f}
              <li class:clickable={!!f.span}>
                <button type="button" disabled={!f.span} on:click={() => peek(f.span)} title={f.span ? 'Show the term in the UPLC view' : ''}>
                  <span class="kind">{f.kind}</span> <span class="detail">{f.detail}</span>
                </button>
              </li>
            {/each}
            {#if (s?.stackDepth ?? 0) > s.frames.length}<li class="muted">… {s.stackDepth - s.frames.length} more</li>{/if}
          </ol>
        {:else}
          <div class="muted small">No pending frames</div>
        {/if}
      </section>

      <section class="pane">
        <h4>Traces <span class="count">{s?.traces.length ?? 0}</span></h4>
        {#if s?.traces.length}
          <ol class="traces">{#each s.traces as tr}<li>{tr}</li>{/each}</ol>
        {:else}<div class="muted small">No traces yet</div>{/if}

        <h4 class="spaced">Breakpoints</h4>
        <label class="check"><input type="checkbox" checked={$breakpoints.onTrace} on:change={(e) => ($breakpoints = { ...$breakpoints, onTrace: e.currentTarget.checked })} /> Pause when a trace is emitted</label>
        <div class="bp-lines">
          {#if $breakpoints.lines.length}
            Lines {$breakpoints.lines.join(', ')} <button type="button" class="link" on:click={() => ($breakpoints = { ...$breakpoints, lines: [] })}>clear</button>
          {:else}<span class="muted">Click the UPLC gutter to add line breakpoints</span>{/if}
        </div>
        <div class="bp-builtins">
          {#each $breakpoints.builtins as b}
            <span class="chip">{b} <button type="button" on:click={() => ($breakpoints = { ...$breakpoints, builtins: $breakpoints.builtins.filter((x) => x !== b) })}>×</button></span>
          {/each}
          <input type="text" list="uplc-builtins" placeholder="+ builtin" bind:value={builtinInput} on:change={addBuiltin} />
          <datalist id="uplc-builtins">{#each knownBuiltins as b}<option value={b}></option>{/each}</datalist>
        </div>
      </section>
    </div>
  {/if}
</div>

<style>
  .debugger { display: flex; flex-direction: column; height: 100%; min-height: 0; }
  .empty { padding: 18px; font-size: 13px; color: var(--text-secondary); display: flex; gap: 8px; align-items: flex-start; max-width: 720px; }
  .empty p { margin: 0 0 8px; }
  kbd { font-family: var(--font-mono); font-size: 10px; background: rgba(0, 0, 0, 0.15); border-radius: 3px; padding: 0 4px; margin-left: 4px; }
  .controls { display: flex; align-items: center; justify-content: space-between; padding: 8px 14px 4px; gap: 10px; flex-wrap: wrap; }
  .buttons { display: flex; gap: 4px; }
  .buttons button { background: var(--bg-surface); color: var(--text-primary); font-size: 12px; padding: 4px 10px; }
  .buttons button:hover:not(:disabled) { background: var(--bg-hover); }
  .buttons button:disabled { opacity: 0.4; cursor: not-allowed; }
  .buttons button.main { background: var(--accent); color: var(--bg-primary); font-weight: 600; }
  .buttons button.stop { color: var(--error); }
  .position { display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--text-secondary); }
  .reason { font-size: 11px; padding: 1px 8px; border-radius: 10px; background: var(--bg-surface); }
  .reason.breakpoint, .reason.builtin { background: rgba(243, 139, 168, 0.18); color: var(--error); }
  .reason.trace { background: rgba(250, 179, 135, 0.18); color: var(--warning); }
  .reason.error { background: var(--error); color: var(--bg-primary); }
  .reason.end { background: rgba(166, 227, 161, 0.18); color: var(--success); }
  .stale { margin: 2px 14px; font-size: 12px; color: var(--warning); }
  .stale.error { color: var(--error); }
  .scrubber { position: relative; padding: 10px 14px 2px; }
  .scrubber input { width: 100%; accent-color: var(--accent); }
  .markers { position: absolute; left: 21px; right: 21px; top: 2px; height: 10px; }
  .marker { position: absolute; width: 4px; height: 10px; padding: 0; border-radius: 2px; transform: translateX(-2px); }
  .marker.trace { background: var(--warning); }
  .marker.error { background: var(--error); width: 6px; transform: translateX(-3px); }
  .panes { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(200px, 1fr) minmax(240px, 1.4fr) minmax(220px, 1.2fr) minmax(220px, 1fr); gap: 1px; background: var(--border); border-top: 1px solid var(--border); overflow: hidden; }
  .pane { background: var(--bg-primary); padding: 8px 12px; overflow: auto; min-width: 0; font-size: 12px; }
  h4 { margin: 0 0 6px; font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--text-secondary); display: flex; align-items: center; gap: 6px; }
  h4.spaced { margin-top: 14px; }
  .count { font-size: 10px; background: var(--bg-surface); color: var(--accent); padding: 0 6px; border-radius: 8px; }
  .filter { margin-left: auto; width: 110px !important; font-size: 11px !important; padding: 1px 6px !important; text-transform: none; }
  .phase { font-weight: 600; color: var(--accent); }
  .phase.done { color: var(--success); }
  .phase.failed { color: var(--error); }
  .phase.return { color: var(--warning); }
  .error-text { color: var(--error); font-family: var(--font-mono); margin-top: 4px; white-space: pre-wrap; }
  .label { margin-top: 8px; font-size: 11px; color: var(--text-muted); }
  .value { margin: 2px 0 0; font-family: var(--font-mono); white-space: pre-wrap; word-break: break-all; color: var(--text-primary); max-height: 110px; overflow: auto; }
  .budget { display: flex; flex-direction: column; gap: 2px; font-family: var(--font-mono); }
  .budget em { color: var(--warning); font-style: normal; margin-left: 6px; font-size: 11px; }
  .env { margin: 0; display: grid; grid-template-columns: max-content 1fr; gap: 2px 10px; }
  .env dt { color: var(--accent); }
  .env dd { margin: 0; min-width: 0; }
  .env-value { display: block; width: 100%; text-align: left; background: transparent; color: var(--text-primary); font-size: 12px; padding: 0 4px; border-radius: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .env-value:hover { background: var(--bg-surface); }
  .env-value.expanded { white-space: pre-wrap; word-break: break-all; background: var(--bg-secondary); }
  .frames, .traces { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 2px; }
  .frames button { background: transparent; color: var(--text-primary); text-align: left; padding: 2px 4px; font-size: 12px; width: 100%; border-radius: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .frames button:disabled { cursor: default; }
  .frames li.clickable button:hover { background: var(--bg-surface); }
  .kind { font-family: var(--font-mono); color: var(--accent); }
  .detail { color: var(--text-secondary); }
  .traces li { color: var(--warning); font-family: var(--font-mono); }
  .check { display: flex; gap: 6px; align-items: center; color: var(--text-secondary); }
  .bp-lines { margin: 6px 0; color: var(--text-secondary); }
  .bp-builtins { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
  .bp-builtins input { width: 150px !important; font-size: 11px !important; padding: 1px 6px !important; }
  .chip { font-size: 11px; background: rgba(243, 139, 168, 0.15); color: var(--error); padding: 1px 4px 1px 8px; border-radius: 10px; font-family: var(--font-mono); }
  .chip button { background: transparent; color: inherit; padding: 0 4px; font-size: 12px; }
  .muted { color: var(--text-muted); }
  .small { font-size: 12px; }
  .mono { font-family: var(--font-mono); }
  .error { color: var(--error); }
  .link { background: transparent; color: var(--accent); font-size: 12px; padding: 0 2px; }
  @media (max-width: 1100px) { .panes { grid-template-columns: 1fr 1fr; overflow: auto; } }
</style>
