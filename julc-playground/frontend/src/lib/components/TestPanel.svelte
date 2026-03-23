<script lang="ts">
  import { onMount } from 'svelte';
  import {
    source, params, datumName, datumFields, redeemerVariants, redeemerFields,
    purpose, evalResult, isEvaluating, contractName
  } from '../stores/editor';
  import { api, type ScenarioItem } from '../api/client';
  import BudgetMeter from './BudgetMeter.svelte';

  let paramValues: Record<string, string> = {};
  let datumValues: Record<string, string> = {};
  let selectedVariant = 0;
  let redeemerFieldValues: Record<string, string> = {};
  let signers: string[] = [''];
  let validRangeAfter: string = '';
  let validRangeBefore: string = '';

  let scenarios: ScenarioItem[] = [];
  let selectedScenario = '';

  // React to purpose changes
  $: if ($purpose) {
    loadScenarios($purpose);
  }

  // Reset form when contract changes
  $: if ($contractName) {
    paramValues = {};
    datumValues = {};
    redeemerFieldValues = {};
    selectedVariant = 0;
    signers = [''];
    validRangeAfter = '';
    validRangeBefore = '';
    selectedScenario = '';
  }

  async function loadScenarios(p: string) {
    try {
      const res = await api.scenarios(p);
      scenarios = res.scenarios;
    } catch {
      scenarios = [];
    }
  }

  function applyScenario() {
    const s = scenarios.find(sc => sc.name === selectedScenario);
    if (!s) return;
    if (s.signers.length > 0) signers = [...s.signers];
    if (s.validRangeAfter != null) validRangeAfter = s.validRangeAfter.toString();
    if (s.validRangeBefore != null) validRangeBefore = s.validRangeBefore.toString();
    if (s.datum) datumValues = { ...s.datum };
    if (s.redeemerVariant != null) selectedVariant = s.redeemerVariant;
    if (s.redeemerFields) redeemerFieldValues = { ...s.redeemerFields };
  }

  function addSigner() { signers = [...signers, '']; }
  function removeSigner(i: number) { signers = signers.filter((_, idx) => idx !== i); }

  function inputType(type: string): string {
    switch (type) {
      case 'Integer': case 'Lovelace': case 'POSIXTime': return 'number';
      default: return 'text';
    }
  }

  function placeholder(type: string): string {
    switch (type) {
      case 'PubKeyHash': case 'ValidatorHash': case 'ScriptHash':
      case 'PolicyId': case 'TokenName': case 'DatumHash': case 'TxId':
      case 'ByteString': return 'hex bytes (e.g. 0101...01)';
      case 'Integer': return '0';
      case 'Lovelace': return 'lovelace amount';
      case 'POSIXTime': return 'POSIX milliseconds';
      case 'Boolean': return 'true or false';
      default: return 'value';
    }
  }

  async function runTest() {
    isEvaluating.set(true);
    evalResult.set(null);
    try {
      const body: any = {
        source: $source,
      };

      // Params
      if ($params.length > 0) {
        body.paramValues = { ...paramValues };
      }

      // Datum
      if ($datumFields.length > 0) {
        body.datum = { ...datumValues };
      }

      // Redeemer
      if ($redeemerVariants.length > 0) {
        body.redeemer = { variant: selectedVariant, fields: { ...redeemerFieldValues } };
      } else if ($redeemerFields.length > 0) {
        body.redeemer = { variant: 0, fields: { ...redeemerFieldValues } };
      }

      // Scenario overrides
      const nonEmptySigners = signers.filter(s => s.trim());
      body.scenario = {
        signers: nonEmptySigners,
        ...(validRangeAfter ? { validRangeAfter: parseInt(validRangeAfter) } : {}),
        ...(validRangeBefore ? { validRangeBefore: parseInt(validRangeBefore) } : {}),
      };

      const res = await api.evaluate(body);
      evalResult.set(res);
    } catch (err: any) {
      evalResult.set({
        success: false, budgetCpu: 0, budgetMem: 0, traces: [],
        error: err.message, diagnostics: []
      });
    } finally {
      isEvaluating.set(false);
    }
  }
</script>

<div class="test-panel">
  <div class="section-header">Test Configuration</div>

  <!-- Scenario picker -->
  {#if scenarios.length > 0}
    <div class="form-group">
      <label>Scenario Template</label>
      <div class="scenario-row">
        <select bind:value={selectedScenario} on:change={applyScenario}>
          <option value="">Custom...</option>
          {#each scenarios as s}
            <option value={s.name}>{s.name}</option>
          {/each}
        </select>
      </div>
    </div>
  {/if}

  <!-- Params -->
  {#if $params.length > 0}
    <div class="form-group">
      <label>Parameters</label>
      {#each $params as param}
        <div class="field-row">
          <span class="field-name">{param.name}</span>
          <span class="field-type">{param.type}</span>
          <input
            type={inputType(param.type)}
            placeholder={placeholder(param.type)}
            bind:value={paramValues[param.name]}
          />
        </div>
      {/each}
    </div>
  {/if}

  <!-- Datum -->
  {#if $datumFields.length > 0}
    <div class="form-group">
      <label>{$datumName ?? 'Datum'}</label>
      {#each $datumFields as field}
        <div class="field-row">
          <span class="field-name">{field.name}</span>
          <span class="field-type">{field.type}</span>
          <input
            type={inputType(field.type)}
            placeholder={placeholder(field.type)}
            bind:value={datumValues[field.name]}
          />
        </div>
      {/each}
    </div>
  {/if}

  <!-- Redeemer -->
  {#if $redeemerVariants.length > 0}
    <div class="form-group">
      <label>Redeemer</label>
      <select bind:value={selectedVariant}>
        {#each $redeemerVariants as v}
          <option value={v.tag}>{v.name}</option>
        {/each}
      </select>
      {#each $redeemerVariants as v}
        {#if v.tag === selectedVariant && v.fields.length > 0}
          {#each v.fields as field}
            <div class="field-row">
              <span class="field-name">{field.name}</span>
              <span class="field-type">{field.type}</span>
              <input
                type={inputType(field.type)}
                placeholder={placeholder(field.type)}
                bind:value={redeemerFieldValues[field.name]}
              />
            </div>
          {/each}
        {/if}
      {/each}
    </div>
  {:else if $redeemerFields.length > 0}
    <div class="form-group">
      <label>Redeemer</label>
      {#each $redeemerFields as field}
        <div class="field-row">
          <span class="field-name">{field.name}</span>
          <span class="field-type">{field.type}</span>
          <input
            type={inputType(field.type)}
            placeholder={placeholder(field.type)}
            bind:value={redeemerFieldValues[field.name]}
          />
        </div>
      {/each}
    </div>
  {/if}

  <!-- Signers -->
  <div class="form-group">
    <label>
      Signers
      <button class="secondary small" on:click={addSigner}>+ Add</button>
    </label>
    {#each signers as signer, i}
      <div class="signer-row">
        <input type="text" placeholder="PubKeyHash hex" bind:value={signers[i]} />
        {#if signers.length > 1}
          <button class="secondary small" on:click={() => removeSigner(i)}>x</button>
        {/if}
      </div>
    {/each}
  </div>

  <!-- Time range -->
  <div class="form-group">
    <label>Time Range</label>
    <div class="field-row">
      <span class="field-name">validAfter</span>
      <input type="number" placeholder="POSIX time" bind:value={validRangeAfter} />
    </div>
    <div class="field-row">
      <span class="field-name">validBefore</span>
      <input type="number" placeholder="POSIX time" bind:value={validRangeBefore} />
    </div>
  </div>

  <!-- Run button -->
  <div class="run-section">
    <button class="primary run-btn" on:click={runTest} disabled={$isEvaluating}>
      {#if $isEvaluating}
        <span class="spinner"></span> Evaluating...
      {:else}
        Run Test
      {/if}
    </button>
  </div>

  <!-- Results -->
  {#if $evalResult}
    <div class="result-section">
      <div class="result-header">
        <span class="badge" class:success={$evalResult.success} class:error={!$evalResult.success}>
          {$evalResult.success ? 'SUCCESS' : 'FAILURE'}
        </span>
      </div>

      {#if $evalResult.error}
        <div class="error-msg">{$evalResult.error}</div>
      {/if}

      {#if $evalResult.budgetCpu > 0 || $evalResult.budgetMem > 0}
        <BudgetMeter cpu={$evalResult.budgetCpu} mem={$evalResult.budgetMem} />
      {/if}

      {#if $evalResult.traces.length > 0}
        <div class="traces">
          <label>Traces</label>
          {#each $evalResult.traces as trace}
            <div class="trace-line">{trace}</div>
          {/each}
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .test-panel {
    padding: 12px;
    overflow-y: auto;
    height: 100%;
    font-size: 13px;
  }

  .section-header {
    font-weight: 600;
    font-size: 14px;
    margin-bottom: 12px;
    color: var(--text-primary);
  }

  .form-group {
    margin-bottom: 14px;
  }

  .form-group > label {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    color: var(--text-muted);
    margin-bottom: 6px;
    letter-spacing: 0.5px;
  }

  .field-row {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 4px;
  }

  .field-name {
    font-family: var(--font-mono);
    font-size: 12px;
    color: var(--text-secondary);
    min-width: 80px;
  }

  .field-type {
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--text-muted);
    min-width: 60px;
  }

  .signer-row {
    display: flex;
    gap: 4px;
    margin-bottom: 4px;
  }

  .signer-row input { flex: 1; }

  .small {
    padding: 2px 8px;
    font-size: 11px;
  }

  .scenario-row {
    display: flex;
    gap: 8px;
  }

  .run-section {
    margin: 16px 0 12px;
  }

  .run-btn {
    width: 100%;
    padding: 10px;
    font-size: 14px;
  }

  .result-section {
    border-top: 1px solid var(--border);
    padding-top: 12px;
  }

  .result-header {
    margin-bottom: 8px;
  }

  .error-msg {
    color: var(--error);
    font-size: 12px;
    font-family: var(--font-mono);
    padding: 6px 8px;
    background: rgba(243, 139, 168, 0.1);
    border-radius: 4px;
    margin-bottom: 8px;
    word-break: break-word;
  }

  .traces {
    margin-top: 8px;
  }

  .traces label {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    color: var(--text-muted);
    margin-bottom: 4px;
    display: block;
  }

  .trace-line {
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--text-secondary);
    padding: 2px 0;
  }
</style>
