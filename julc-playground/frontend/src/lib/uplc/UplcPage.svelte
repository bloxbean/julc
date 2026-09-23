<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import CodeView from './CodeView.svelte';
  import ContextView from './ContextView.svelte';
  import DebuggerPanel from './DebuggerPanel.svelte';
  import MockTxEditor from './MockTxEditor.svelte';
  import ResultPanel from './ResultPanel.svelte';
  import ScriptBar from './ScriptBar.svelte';
  import SourceDebugView from './SourceDebugView.svelte';
  import StatsView from './StatsView.svelte';
  import {
    breakpoints, debugBack, debugContinue, debugOut, debugOver, debugState, debugStep, decodeState, decompile,
    evalState, evaluate, javaState, outputTab, peekSpan, startDebug, toggleBreakpointLine, viewTab,
  } from './store';

  const OUTPUT_KEY = 'julc.uplc.outputHeight';
  let outputHeight = Math.round(Math.min(360, Math.max(220, window.innerHeight * 0.36)));
  try {
    outputHeight = Number(localStorage.getItem(OUTPUT_KEY)) || outputHeight;
  } catch {
    // storage unavailable
  }

  $: decoded = $decodeState.response?.ok ? $decodeState.response : null;
  $: uplcText = decoded?.uplcText ?? '';
  $: canRun = !!decoded;
  $: if ($viewTab === 'java' && decoded) decompile();
  $: currentSpan = $debugState.active && !$debugState.stale ? $debugState.snapshot?.span ?? null : null;
  $: failedSpan = !$debugState.active && !$evalState.stale && $evalState.response?.status !== 'success' ? $evalState.response?.failedSpan ?? null : null;
  $: running = $evalState.loading || $debugState.loading;

  function onKey(e: KeyboardEvent) {
    const debugging = $debugState.active && !$debugState.stale;
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault();
      if (canRun) evaluate();
    } else if (e.key === 'F5') {
      e.preventDefault();
      if (!canRun) return;
      if (debugging && !e.shiftKey) debugContinue();
      else startDebug();
    } else if (debugging && e.key === 'F8') {
      e.preventDefault();
      debugContinue();
    } else if (debugging && e.key === 'F10') {
      e.preventDefault();
      e.shiftKey ? debugBack() : debugOver();
    } else if (debugging && e.key === 'F11') {
      e.preventDefault();
      e.shiftKey ? debugOut() : debugStep();
    }
  }

  let dragStart: { y: number; height: number } | null = null;
  function beginResize(e: PointerEvent) {
    dragStart = { y: e.clientY, height: outputHeight };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }
  function resize(e: PointerEvent) {
    if (!dragStart) return;
    outputHeight = Math.min(Math.max(140, dragStart.height - (e.clientY - dragStart.y)), window.innerHeight - 220);
  }
  function endResize() {
    dragStart = null;
    try {
      localStorage.setItem(OUTPUT_KEY, String(Math.round(outputHeight)));
    } catch {
      // storage unavailable
    }
  }

  onMount(() => window.addEventListener('keydown', onKey));
  onDestroy(() => window.removeEventListener('keydown', onKey));
</script>

<div class="uplc-page">
  <ScriptBar />

  <div class="work">
    <div class="viewer">
      <div class="tabs">
        <button type="button" class:active={$viewTab === 'uplc'} on:click={() => viewTab.set('uplc')}>UPLC</button>
        <button type="button" class:active={$viewTab === 'source'} on:click={() => viewTab.set('source')}>Java Source <span class="tag">experimental</span></button>
        <button type="button" class:active={$viewTab === 'java'} on:click={() => viewTab.set('java')}>Decompiled</button>
        <button type="button" class:active={$viewTab === 'stats'} on:click={() => viewTab.set('stats')}>Details</button>
        <span class="spacer"></span>
        {#if $viewTab === 'uplc' && $breakpoints.lines.length}
          <span class="hint">{$breakpoints.lines.length} breakpoint{$breakpoints.lines.length > 1 ? 's' : ''}</span>
        {:else if $viewTab === 'uplc' && decoded}
          <span class="hint">Click the gutter to set a breakpoint</span>
        {/if}
      </div>
      <div class="view-body">
        {#if $viewTab === 'source'}
          <SourceDebugView />
        {:else if !decoded}
          <div class="placeholder">
            {#if $decodeState.loading}<span class="spinner"></span> Decoding…
            {:else if $decodeState.error}<div class="error">{$decodeState.error}</div>
            {:else}Paste a script above or pick an example.{/if}
          </div>
        {:else if $viewTab === 'uplc'}
          <CodeView text={uplcText} language="uplc" {currentSpan} {failedSpan} peekSpan={$peekSpan}
            breakpointLines={$breakpoints.lines} onToggleBreakpoint={toggleBreakpointLine} />
        {:else if $viewTab === 'java'}
          {#if $javaState.loading}
            <div class="placeholder"><span class="spinner"></span> Decompiling…</div>
          {:else if $javaState.response?.ok && $javaState.response.javaSource}
            <div class="java">
              <div class="note">Decompiled preview for reading. It reconstructs structure from UPLC and may not compile as-is.</div>
              <div class="java-code"><CodeView text={$javaState.response.javaSource} language="java" /></div>
            </div>
          {:else}
            <div class="placeholder"><div class="error">{$javaState.error ?? $javaState.response?.error ?? 'Decompilation unavailable'}</div></div>
          {/if}
        {:else}
          <StatsView />
        {/if}
      </div>
    </div>

    <aside class="tx">
      <div class="tx-head">
        <span class="tx-title">Mock transaction</span>
        <span class="tx-sub">prefilled · edit only what your script checks</span>
      </div>
      <div class="tx-body"><MockTxEditor /></div>
      <div class="tx-actions">
        <button type="button" class="primary run" disabled={!canRun || running} on:click={evaluate}>
          {#if $evalState.loading}<span class="spinner"></span>{:else}▶{/if} Run <kbd>⌘⏎</kbd>
        </button>
        <button type="button" class="secondary debug" disabled={!canRun || running} on:click={() => startDebug()}>
          Debug <kbd>F5</kbd>
        </button>
      </div>
    </aside>
  </div>

  <div class="resizer" role="separator" aria-orientation="horizontal" on:pointerdown={beginResize} on:pointermove={resize} on:pointerup={endResize}></div>

  <div class="output" style="height: {outputHeight}px">
    <div class="tabs">
      <button type="button" class:active={$outputTab === 'result'} on:click={() => outputTab.set('result')}>
        Result
        {#if $evalState.response?.ok}<span class="dot" class:ok={$evalState.response.status === 'success' && $evalState.response.accepted}></span>{/if}
      </button>
      <button type="button" class:active={$outputTab === 'debugger'} on:click={() => outputTab.set('debugger')}>
        Debugger {#if $debugState.active}<span class="dot live"></span>{/if}
      </button>
      <button type="button" class:active={$outputTab === 'context'} on:click={() => outputTab.set('context')}>Script context</button>
    </div>
    <div class="output-body">
      {#if $outputTab === 'result'}<ResultPanel />
      {:else if $outputTab === 'debugger'}<DebuggerPanel />
      {:else}<ContextView />{/if}
    </div>
  </div>
</div>

<style>
  .uplc-page { flex: 1; display: flex; flex-direction: column; min-height: 0; }
  .work { flex: 1; display: flex; min-height: 0; }
  .viewer { flex: 1; min-width: 0; display: flex; flex-direction: column; border-right: 1px solid var(--border); }
  .tabs { display: flex; align-items: center; gap: 2px; padding: 5px 10px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
  .tabs button { background: none; color: var(--text-secondary); padding: 4px 10px; font-size: 12px; border-radius: 5px; display: inline-flex; align-items: center; gap: 6px; }
  .tabs button.active { background: var(--bg-surface); color: var(--text-primary); }
  .tabs button:hover:not(.active) { color: var(--text-primary); }
  .tag { font-size: 9px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--text-muted); border: 1px solid var(--border); border-radius: 3px; padding: 0 3px; }
  .spacer { flex: 1; }
  .hint { font-size: 11px; color: var(--text-muted); }
  .view-body { flex: 1; min-height: 0; position: relative; }
  .placeholder { padding: 24px; color: var(--text-muted); font-size: 13px; display: flex; gap: 8px; align-items: center; }
  .error { color: var(--error); font-family: var(--font-mono); font-size: 12px; white-space: pre-wrap; }
  .java { display: flex; flex-direction: column; height: 100%; }
  .note { font-size: 11px; color: var(--text-muted); padding: 5px 12px; background: var(--bg-secondary); border-bottom: 1px solid var(--border); }
  .java-code { flex: 1; min-height: 0; }
  .tx { width: 460px; flex-shrink: 0; display: flex; flex-direction: column; min-height: 0; background: var(--bg-primary); }
  .tx-head { padding: 8px 14px 6px; display: flex; align-items: baseline; gap: 8px; }
  .tx-title { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.4px; color: var(--text-secondary); }
  .tx-sub { font-size: 11px; color: var(--text-muted); }
  .tx-body { flex: 1; overflow: auto; padding: 0 14px 8px; }
  .tx-actions { display: flex; gap: 8px; padding: 10px 14px; border-top: 1px solid var(--border); background: var(--bg-secondary); }
  .tx-actions button { flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 6px; font-size: 13px; padding: 7px 12px; }
  .tx-actions button:disabled { opacity: 0.5; cursor: not-allowed; }
  .tx-actions .run { flex: 1.4; }
  kbd { font-family: var(--font-mono); font-size: 10px; background: rgba(0, 0, 0, 0.18); border-radius: 3px; padding: 0 4px; font-weight: normal; }
  .resizer { height: 5px; cursor: row-resize; background: var(--border); flex-shrink: 0; touch-action: none; }
  .resizer:hover { background: var(--accent); }
  .output { flex-shrink: 0; display: flex; flex-direction: column; min-height: 0; background: var(--bg-primary); }
  .output-body { flex: 1; min-height: 0; overflow: auto; }
  .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--error); display: inline-block; }
  .dot.ok { background: var(--success); }
  .dot.live { background: var(--warning); }
  @media (max-width: 1000px) { .tx { width: 380px; } }
</style>
