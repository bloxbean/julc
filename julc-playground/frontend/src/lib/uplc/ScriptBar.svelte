<script lang="ts">
  import DataField from './DataField.svelte';
  import { EXAMPLES } from './examples';
  import { purposeFromContract } from './mock';
  import { compileResult, purpose as contractPurpose } from '../stores/editor';
  import { decodeState, language, loadExample, openScript, params, scriptText, validator } from './store';

  let showParams = false;
  let copied = false;

  $: info = $decodeState.response?.info ?? null;
  $: sizeText = info ? (info.flatBytes >= 1024 ? `${(info.flatBytes / 1024).toFixed(1)} KB` : `${info.flatBytes} B`) : '';

  function pickExample(e: Event) {
    const select = e.target as HTMLSelectElement;
    const example = EXAMPLES[Number(select.value)];
    select.value = '';
    if (example) {
      loadExample(example);
      showParams = example.params.length > 0;
    }
  }

  function fromContract() {
    const code = $compileResult?.compiledCode;
    if (!code) return;
    openScript(code, purposeFromContract($contractPurpose));
  }

  async function copyHash() {
    if (!info) return;
    try {
      await navigator.clipboard.writeText(info.scriptHash);
      copied = true;
      setTimeout(() => (copied = false), 1200);
    } catch {
      // clipboard unavailable
    }
  }

  const addParam = () => { $params = [...$params, { format: 'uplc', value: 'I 0' }]; showParams = true; };
  const removeParam = (i: number) => ($params = $params.filter((_, j) => j !== i));
</script>

<div class="script-bar">
  <div class="input-row">
    <textarea class="script" rows="2" spellcheck="false" bind:value={$scriptText}
      placeholder="Paste a compiled script: CBOR hex, plutus.json blueprint, text envelope, or UPLC text"></textarea>
    <div class="actions">
      <select class="examples" on:change={pickExample} value="">
        <option value="" disabled>Examples</option>
        {#each EXAMPLES as example, i}<option value={i} title={example.description}>{example.name}</option>{/each}
      </select>
      <button type="button" class="secondary" disabled={!$compileResult?.compiledCode} on:click={fromContract}
        title={$compileResult?.compiledCode ? 'Load the script compiled in the Contract tab' : 'Compile a contract in the Contract tab first'}>From Contract</button>
    </div>
  </div>

  <div class="chips">
    {#if $decodeState.loading && !info}
      <span class="chip muted"><span class="spinner"></span> decoding…</span>
    {:else if $decodeState.error}
      <span class="chip error" title={$decodeState.error}>✕ {$decodeState.error}</span>
    {/if}
    {#if info}
      <span class="chip" title="Input format and CBOR wrapping">{info.inputFormat === 'hex' ? info.wrapping : `${info.inputFormat} · ${info.wrapping}`}</span>
      <label class="chip select" title={`Plutus language (${info.languageSource})`}>
        <select bind:value={$language}>
          <option value="auto">Auto · {info.language}</option>
          <option value="V1">Plutus V1</option>
          <option value="V2">Plutus V2</option>
          <option value="V3">Plutus V3</option>
        </select>
      </label>
      <span class="chip">UPLC {info.programVersion}</span>
      <span class="chip" title={`${info.flatBytes} bytes of FLAT · ${info.termCount} terms`}>{sizeText} · {info.termCount.toLocaleString()} terms</span>
      <button type="button" class="chip hash" on:click={copyHash} title={`Script hash ${info.scriptHash} (click to copy)`}>
        hash <span class="mono">{info.scriptHash.slice(0, 8)}…{info.scriptHash.slice(-6)}</span> {copied ? '✓' : '⧉'}
      </button>
      <span class="chip" title={info.builtins.join(', ')}>{info.builtins.length} builtins</span>
      {#if info.validators.length > 1}
        <label class="chip select" title="Validator from the blueprint">
          <select bind:value={$validator}>
            <option value="">{info.validator}</option>
            {#each info.validators.filter((v) => v !== info?.validator) as v}<option value={v}>{v}</option>{/each}
          </select>
        </label>
      {/if}
      {#each info.warnings as w}<span class="chip warn" title={w}>⚠ {w}</span>{/each}
    {/if}
    <button type="button" class="chip params" class:on={$params.length > 0} on:click={() => ($params.length ? (showParams = !showParams) : addParam())}
      title="Apply parameters to a parameterized script before evaluation">
      {$params.length ? `${info?.paramsApplied ?? $params.length} param${$params.length > 1 ? 's' : ''} applied ${showParams ? '▴' : '▾'}` : '+ Params'}
    </button>
  </div>

  {#if showParams && $params.length > 0}
    <div class="params-editor">
      {#each $params as _, i}
        <div class="param">
          <span class="index">#{i + 1}</span>
          <div class="grow"><DataField bind:value={$params[i]} compact /></div>
          <button type="button" class="remove" on:click={() => removeParam(i)} title="Remove parameter">×</button>
        </div>
      {/each}
      <button type="button" class="link" on:click={addParam}>+ parameter</button>
    </div>
  {/if}
</div>

<style>
  .script-bar { padding: 10px 14px 8px; border-bottom: 1px solid var(--border); background: var(--bg-secondary); display: flex; flex-direction: column; gap: 7px; }
  .input-row { display: flex; gap: 10px; align-items: stretch; }
  .script {
    flex: 1; font-family: var(--font-mono); font-size: 12px; line-height: 1.45; resize: vertical; min-height: 40px; max-height: 180px;
    background: var(--bg-primary); color: var(--text-secondary); border: 1px solid var(--border); border-radius: 6px; padding: 6px 8px; word-break: break-all;
  }
  .script:focus { outline: none; border-color: var(--accent); color: var(--text-primary); }
  .actions { display: flex; flex-direction: column; gap: 6px; width: 150px; }
  .actions select, .actions button { font-size: 12px; width: 100%; }
  .actions button:disabled { opacity: 0.5; cursor: not-allowed; }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
  .chip { font-size: 11px; background: var(--bg-surface); color: var(--text-secondary); padding: 2px 9px; border-radius: 10px; white-space: nowrap; max-width: 420px; overflow: hidden; text-overflow: ellipsis; display: inline-flex; align-items: center; gap: 4px; }
  .chip.muted { color: var(--text-muted); }
  .chip.error { background: rgba(243, 139, 168, 0.15); color: var(--error); }
  .chip.warn { background: rgba(250, 179, 135, 0.12); color: var(--warning); }
  .chip.select { padding: 0; }
  .chip.select select { background: transparent; border: none; color: var(--accent); font-size: 11px; padding: 2px 6px; }
  button.chip { border: none; cursor: pointer; }
  button.chip:hover { background: var(--bg-hover); }
  .chip.params { color: var(--accent); background: transparent; border: 1px dashed var(--border); }
  .chip.params.on { border-style: solid; }
  .mono { font-family: var(--font-mono); }
  .params-editor { display: flex; flex-direction: column; gap: 6px; padding: 4px 0 2px; }
  .param { display: flex; gap: 8px; align-items: flex-start; }
  .index { font-size: 11px; color: var(--text-muted); padding-top: 3px; width: 22px; }
  .grow { flex: 1; min-width: 0; }
  .remove { background: transparent; color: var(--text-muted); padding: 0 8px; font-size: 15px; }
  .remove:hover { color: var(--error); }
  .link { align-self: flex-start; background: transparent; color: var(--accent); font-size: 11px; padding: 2px 4px; }
</style>
