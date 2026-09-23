<script lang="ts">
  import { get } from 'svelte/store';
  import CodeView from './CodeView.svelte';
  import DataField from './DataField.svelte';
  import ExamplePicker from '../components/ExamplePicker.svelte';
  import { api } from '../api/client';
  import {
    breakpoints, compileSourceDebug, debugState, setPurpose, setSourceDebugCode, sourceDebugParams,
    sourceDebugSource, sourceDebugState, toggleJavaBreakpointLine, useSourceDebugCode,
  } from './store';
  import { librarySource as contractLibrary, purpose as contractPurpose, source as contractSource } from '../stores/editor';
  import { engine, wasmError, wasmFeatures, wasmStatus } from '../stores/engine';
  import type { PurposeType, Span } from './types';

  $: location = $debugState.active && !$debugState.stale ? $debugState.snapshot?.javaLocation ?? null : null;
  $: currentSpan = location ? {
    startLine: location.line,
    startColumn: Math.max(1, location.column),
    endLine: location.line,
    endColumn: Math.max(2, location.column + Math.max(1, location.fragment?.length ?? 1)),
  } satisfies Span : null;
  $: executableLines = $sourceDebugState.response?.executableJavaLines ?? [];
  $: unbound = ($breakpoints.javaLines ?? []).filter((line) => !executableLines.includes(line));
  $: wasmSourceDebugReady = $engine !== 'wasm'
    || ($wasmStatus === 'ready' && !!$wasmFeatures?.groups.includes('sourceDebug'));
  $: wasmSourceDebugUnsupported = $engine === 'wasm' && $wasmStatus === 'ready' && !!$wasmFeatures
    && !$wasmFeatures?.groups.includes('sourceDebug');

  function purpose(value: string | null): PurposeType | null {
    return ({ SPENDING: 'spend', MINTING: 'mint', WITHDRAW: 'reward', CERTIFYING: 'certify',
      VOTING: 'vote', PROPOSING: 'propose' } as Record<string, PurposeType>)[value ?? ''] ?? null;
  }

  async function selectSource(value: string) {
    useSourceDebugCode(value);
    try {
      const checked = await api.check(value);
      const selected = purpose(checked.purpose);
      if (selected) setPurpose(selected);
    } catch {
      // Compilation will show the actionable diagnostic.
    }
  }

  function useContract() {
    useSourceDebugCode(get(contractSource), get(contractLibrary));
    const selected = purpose(get(contractPurpose));
    if (selected) setPurpose(selected);
  }

  if (!get(sourceDebugSource).trim()) useContract();
</script>

<div class="source-debug">
  <div class="source-actions">
    <span class="title">Java source</span>
    <ExamplePicker onSelect={selectSource} />
    <button type="button" class="secondary" on:click={useContract}>Use Contract source</button>
    <span class="spacer"></span>
    {#if $sourceDebugState.response?.ok && $sourceDebugState.stale}<span class="stale">Changed — recompile required</span>{/if}
    <button type="button" class="primary"
      disabled={$sourceDebugState.loading || !$sourceDebugSource.trim() || !wasmSourceDebugReady}
      on:click={compileSourceDebug}>
      {#if $sourceDebugState.loading}<span class="spinner"></span>{/if}
      Compile &amp; start debugging
    </button>
  </div>

  <div class="warning">
    <strong>Experimental source-debug build.</strong> The UPLC optimizer is disabled. Script bytes, hash and
    execution budget may differ from normal compilation; do not use this budget as a normal-build estimate.
  </div>

  {#if wasmSourceDebugUnsupported}
    <div class="message error">
      This WebAssembly engine predates Java source debugging. Rebuild the full engine from this checkout or copy
      the complete matching static-playground artifact; do not combine a new UI with an older wasm directory.
    </div>
  {:else if $engine === 'wasm' && $wasmStatus === 'error' && $wasmError}
    <div class="message error">{$wasmError}</div>
  {/if}

  {#if $sourceDebugState.error}<div class="message error">{$sourceDebugState.error}</div>{/if}
  {#if $sourceDebugState.response?.diagnostics?.length}
    <div class="diagnostics">
      {#each $sourceDebugState.response.diagnostics as diagnostic}
        <div class:error={diagnostic.level === 'ERROR'}>{diagnostic.level}: {diagnostic.message}</div>
      {/each}
    </div>
  {/if}

  {#if $sourceDebugState.response?.params?.length}
    <div class="parameters">
      <span class="param-title">Source parameters</span>
      {#each $sourceDebugState.response.params as param, index}
        <DataField bind:value={$sourceDebugParams[index]} compact label={`${param.name}: ${param.type}`} />
      {/each}
    </div>
  {/if}

  {#if unbound.length}
    <div class="message">Unbound Java breakpoint{unbound.length > 1 ? 's' : ''}: {unbound.join(', ')}. No executable JuLC term maps to {unbound.length > 1 ? 'these lines' : 'this line'}.</div>
  {/if}

  <div class="editor">
    <CodeView text={$sourceDebugSource} language="java" readOnly={false} onChange={setSourceDebugCode}
      {currentSpan} breakpointLines={$breakpoints.javaLines ?? []} breakpointResolvedLines={executableLines}
      onToggleBreakpoint={toggleJavaBreakpointLine} />
  </div>

  {#if $sourceDebugState.response?.ok}
    <div class="artifact">
      Source-debug artifact · {$sourceDebugState.response.scriptSizeBytes.toLocaleString()} bytes ·
      <span class="mono">{$sourceDebugState.response.scriptHash}</span>
    </div>
  {/if}
</div>

<style>
  .source-debug { height: 100%; display: flex; flex-direction: column; min-height: 0; }
  .source-actions { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--bg-secondary); border-bottom: 1px solid var(--border); flex-wrap: wrap; }
  .title { font-size: 12px; font-weight: 700; color: var(--text-secondary); }
  .spacer { flex: 1; }
  .source-actions button { font-size: 11px; padding: 4px 9px; }
  .source-actions button:disabled { opacity: .5; cursor: not-allowed; }
  .stale { color: var(--warning); font-size: 11px; }
  .warning { padding: 6px 12px; font-size: 11px; color: var(--warning); background: rgba(250, 179, 135, .09); border-bottom: 1px solid rgba(250, 179, 135, .24); }
  .message, .diagnostics { padding: 5px 12px; font-size: 11px; color: var(--text-secondary); background: var(--bg-secondary); border-bottom: 1px solid var(--border); }
  .error, .diagnostics .error { color: var(--error); }
  .parameters { padding: 7px 12px; display: flex; flex-direction: column; gap: 6px; background: var(--bg-secondary); border-bottom: 1px solid var(--border); }
  .param-title { font-size: 11px; color: var(--text-secondary); font-weight: 700; }
  .editor { flex: 1; min-height: 0; }
  .artifact { padding: 4px 10px; font-size: 10px; color: var(--text-muted); background: var(--bg-secondary); border-top: 1px solid var(--border); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mono { font-family: var(--font-mono); }
</style>
