<script lang="ts">
  import CredentialField from './CredentialField.svelte';
  import DataField from './DataField.svelte';
  import OutputEditor from './OutputEditor.svelte';
  import Section from './Section.svelte';
  import { PURPOSES, SELF, WALLETS, ada, credentialLabel, formatAda } from './mock';
  import { setPurpose, transaction as tx } from './store';
  import type { PurposeType } from './types';

  let moreOpen = false;
  let extra = { withdrawals: false, certificates: false, votes: false, proposals: false, datums: false, treasury: false };

  $: purpose = $tx.purpose.type;
  $: spentInput = purpose === 'spend' ? $tx.inputs[$tx.purpose.index ?? 0] : undefined;
  $: showWithdrawals = extra.withdrawals || $tx.withdrawals.length > 0 || purpose === 'reward';
  $: showCertificates = extra.certificates || $tx.certificates.length > 0 || purpose === 'certify';
  $: showVotes = extra.votes || $tx.votes.length > 0 || purpose === 'vote';
  $: showProposals = extra.proposals || $tx.proposals.length > 0 || purpose === 'propose';
  $: showDatums = extra.datums || $tx.datums.length > 0;
  $: showTreasury = extra.treasury || !!$tx.currentTreasuryAmount || !!$tx.treasuryDonation;

  function choosePurpose(type: PurposeType) {
    if (type !== purpose) setPurpose(type);
  }

  function addInput() {
    $tx.inputs = [...$tx.inputs, { txId: 'feed'.padEnd(64, '0'), index: $tx.inputs.length, address: { payment: `key:${WALLETS[1].keyHash}` }, value: ada(5), datum: { kind: 'none' } }];
  }
  function addReferenceInput() {
    $tx.referenceInputs = [...$tx.referenceInputs, { txId: 'aefe'.padEnd(64, '0'), index: $tx.referenceInputs.length, address: { payment: `key:${WALLETS[2].keyHash}` }, value: ada(2), datum: { kind: 'inline', data: { format: 'uplc', value: 'Constr 0 []' } } }];
  }
  function addOutput() {
    $tx.outputs = [...$tx.outputs, { address: { payment: `key:${WALLETS[1].keyHash}` }, value: ada(2), datum: { kind: 'none' } }];
  }
  function addMint() {
    $tx.mint = [...$tx.mint, { policyId: SELF, tokenName: '746f6b656e', quantity: '1' }];
  }
  function addSigner(keyHash: string) {
    if (!$tx.signatories.includes(keyHash)) $tx.signatories = [...$tx.signatories, keyHash];
  }
  const remove = <T>(list: T[], i: number) => list.filter((_, j) => j !== i);

  function setBound(which: 'from' | 'to', e: Event) {
    const text = (e.target as HTMLInputElement).value.trim();
    $tx.validRange = { ...$tx.validRange, [which]: text === '' ? null : Number(text) };
  }

  function nowMs() {
    return Math.floor(Date.now() / 1000) * 1000;
  }

  $: validitySummary = $tx.validRange.from == null && $tx.validRange.to == null ? 'always'
    : `${$tx.validRange.from ?? '−∞'} … ${$tx.validRange.to ?? '+∞'}`;
</script>

<div class="mock-tx">
  <div class="purpose" role="radiogroup" aria-label="Script purpose">
    {#each PURPOSES as p}
      <button type="button" role="radio" aria-checked={purpose === p.type} class:active={purpose === p.type} title={p.hint}
        on:click={() => choosePurpose(p.type)}>{p.label}</button>
    {/each}
  </div>

  <div class="essentials">
    <DataField label="Redeemer" bind:value={$tx.redeemer} />
    {#if purpose === 'spend'}
      <div class="spent">
        <span class="label">Spending</span>
        <select bind:value={$tx.purpose.index}>
          {#each $tx.inputs as input, i}
            <option value={i}>input #{i} · {credentialLabel(input.address.payment)} · {formatAda(input.value.lovelace)}</option>
          {/each}
        </select>
      </div>
      {#if spentInput}
        {#if spentInput.datum && spentInput.datum.kind !== 'none' && spentInput.datum.data}
          <DataField label="Datum" bind:value={spentInput.datum.data} />
        {:else}
          <p class="hint">The spent input has no datum. <button type="button" class="link" on:click={() => { spentInput.datum = { kind: 'inline', data: { format: 'uplc', value: 'Constr 0 []' } }; $tx = $tx; }}>Add an inline datum</button></p>
        {/if}
        {#if spentInput.address.payment !== SELF}
          <p class="warn">The spent input is not locked by this script. <button type="button" class="link" on:click={() => { spentInput.address.payment = SELF; $tx = $tx; }}>Lock it at this script</button></p>
        {/if}
      {/if}
    {:else if purpose === 'mint'}
      <div class="spent"><span class="label">Policy</span><CredentialField bind:value={$tx.purpose.target} /></div>
    {:else if purpose === 'reward' || purpose === 'vote'}
      <div class="spent">
        <span class="label">{purpose === 'reward' ? 'Credential' : 'Voter'}</span>
        {#if purpose === 'vote'}
          <select bind:value={$tx.purpose.voterType}><option value="drep">DRep</option><option value="committee">Committee</option><option value="pool">Pool</option></select>
        {/if}
        <CredentialField bind:value={$tx.purpose.target} />
      </div>
    {/if}
  </div>

  <Section title="Inputs" count={$tx.inputs.length} onAdd={addInput}
    summary={$tx.inputs.map((i) => `${credentialLabel(i.address.payment)} ${formatAda(i.value.lovelace)}`).join(' · ')}>
    {#each $tx.inputs as input, i}
      <OutputEditor bind:output={$tx.inputs[i]} showRef>
        <button slot="actions" type="button" class="remove" title="Remove input" on:click={() => ($tx.inputs = remove($tx.inputs, i))}>×</button>
      </OutputEditor>
    {/each}
  </Section>

  <Section title="Outputs" count={$tx.outputs.length} onAdd={addOutput}
    summary={$tx.outputs.map((o) => `${credentialLabel(o.address.payment)} ${formatAda(o.value.lovelace)}`).join(' · ')}>
    {#each $tx.outputs as output, i}
      <OutputEditor bind:output={$tx.outputs[i]}>
        <button slot="actions" type="button" class="remove" title="Remove output" on:click={() => ($tx.outputs = remove($tx.outputs, i))}>×</button>
      </OutputEditor>
    {/each}
  </Section>

  <Section title="Signers" count={$tx.signatories.length} summary={$tx.signatories.map((s) => credentialLabel(`key:${s}`)).join(', ')} open={purpose === 'spend'}>
    <div class="chips">
      {#each WALLETS as w}
        <button type="button" class="wallet" class:on={$tx.signatories.includes(w.keyHash)}
          on:click={() => ($tx.signatories.includes(w.keyHash) ? ($tx.signatories = $tx.signatories.filter((s) => s !== w.keyHash)) : addSigner(w.keyHash))}>
          {$tx.signatories.includes(w.keyHash) ? '✓ ' : '+ '}{w.name}
        </button>
      {/each}
      <button type="button" class="wallet" on:click={() => addSigner('00'.repeat(28))}>+ custom</button>
    </div>
    {#each $tx.signatories as signer, i}
      {#if !WALLETS.some((w) => w.keyHash === signer)}
        <div class="line"><input type="text" class="mono grow" bind:value={$tx.signatories[i]} /><button type="button" class="remove" on:click={() => ($tx.signatories = remove($tx.signatories, i))}>×</button></div>
      {/if}
    {/each}
  </Section>

  <Section title="Validity" summary={validitySummary}>
    <div class="line">
      <span class="key">From</span>
      <input type="text" class="mono" placeholder="−∞ (POSIX ms)" value={$tx.validRange.from ?? ''} on:change={(e) => setBound('from', e)} />
      <button type="button" class="link" on:click={() => ($tx.validRange = { ...$tx.validRange, from: nowMs() })}>now</button>
    </div>
    <div class="line">
      <span class="key">To</span>
      <input type="text" class="mono" placeholder="+∞ (POSIX ms)" value={$tx.validRange.to ?? ''} on:change={(e) => setBound('to', e)} />
      <button type="button" class="link" on:click={() => ($tx.validRange = { ...$tx.validRange, to: nowMs() + 3_600_000 })}>now + 1h</button>
    </div>
  </Section>

  <Section title="Mint" count={$tx.mint.length} onAdd={addMint} summary={$tx.mint.map((m) => `${m.quantity} ${m.policyId === SELF ? 'self' : m.policyId.slice(0, 6)}.${m.tokenName}`).join(', ')}>
    {#each $tx.mint as asset, i}
      <div class="line">
        <input type="text" class="mono" bind:value={asset.policyId} title="Policy id ($self = this script)" />
        <input type="text" class="mono" bind:value={asset.tokenName} title="Token name (hex)" />
        <input type="text" class="qty" bind:value={asset.quantity} title="Quantity (negative burns)" />
        <button type="button" class="remove" on:click={() => ($tx.mint = remove($tx.mint, i))}>×</button>
      </div>
    {/each}
  </Section>

  <Section title="Reference inputs" count={$tx.referenceInputs.length} onAdd={addReferenceInput}>
    {#each $tx.referenceInputs as input, i}
      <OutputEditor bind:output={$tx.referenceInputs[i]} showRef>
        <button slot="actions" type="button" class="remove" on:click={() => ($tx.referenceInputs = remove($tx.referenceInputs, i))}>×</button>
      </OutputEditor>
    {/each}
  </Section>

  <Section title="Fee" summary={formatAda($tx.fee)}>
    <div class="line"><span class="key">Lovelace</span><input type="text" class="mono" bind:value={$tx.fee} /></div>
  </Section>

  {#if showWithdrawals}
    <Section title="Withdrawals" count={$tx.withdrawals.length} open={purpose === 'reward'} onAdd={() => ($tx.withdrawals = [...$tx.withdrawals, { credential: SELF, amount: '0' }])}>
      {#each $tx.withdrawals as w, i}
        <div class="line"><CredentialField bind:value={w.credential} allowBech32 /><input type="text" class="qty" bind:value={w.amount} title="Lovelace" /><button type="button" class="remove" on:click={() => ($tx.withdrawals = remove($tx.withdrawals, i))}>×</button></div>
      {/each}
    </Section>
  {/if}

  {#if showCertificates}
    <Section title="Certificates" count={$tx.certificates.length} open={purpose === 'certify'} onAdd={() => ($tx.certificates = [...$tx.certificates, { type: 'regStaking', credential: SELF, deposit: '2000000' }])}>
      {#each $tx.certificates as c, i}
        <div class="line">
          <span class="key">#{i}</span>
          <select bind:value={c.type}>
            <option value="regStaking">register stake</option><option value="unregStaking">deregister stake</option>
            <option value="delegStaking">delegate stake</option><option value="regDRep">register DRep</option>
            <option value="unregDRep">deregister DRep</option><option value="updateDRep">update DRep</option>
          </select>
          <CredentialField bind:value={c.credential} />
          {#if c.type === 'delegStaking'}<input type="text" class="mono" bind:value={c.poolId} placeholder="pool key hash" />{:else if c.type !== 'updateDRep'}<input type="text" class="qty" bind:value={c.deposit} placeholder="deposit" />{/if}
          <button type="button" class="remove" on:click={() => ($tx.certificates = remove($tx.certificates, i))}>×</button>
        </div>
      {/each}
      {#if purpose === 'certify'}
        <div class="line"><span class="key">Script</span><select bind:value={$tx.purpose.index}>{#each $tx.certificates as _, i}<option value={i}>certificate #{i}</option>{/each}</select></div>
      {/if}
    </Section>
  {/if}

  {#if showVotes}
    <Section title="Votes" count={$tx.votes.length} open={purpose === 'vote'} onAdd={() => ($tx.votes = [...$tx.votes, { voterType: 'drep', voter: SELF, actionTxId: '90e1'.padEnd(64, '0'), actionIndex: 0, vote: 'yes' }])}>
      {#each $tx.votes as v, i}
        <div class="line">
          <select bind:value={v.voterType}><option value="drep">DRep</option><option value="committee">Committee</option><option value="pool">Pool</option></select>
          <CredentialField bind:value={v.voter} />
          <select bind:value={v.vote}><option value="yes">yes</option><option value="no">no</option><option value="abstain">abstain</option></select>
          <button type="button" class="remove" on:click={() => ($tx.votes = remove($tx.votes, i))}>×</button>
        </div>
        <div class="line sub"><span class="key">Action</span><input type="text" class="mono grow" bind:value={v.actionTxId} /><input type="number" class="index" bind:value={v.actionIndex} min="0" /></div>
      {/each}
    </Section>
  {/if}

  {#if showProposals}
    <Section title="Proposals" count={$tx.proposals.length} open={purpose === 'propose'} onAdd={() => ($tx.proposals = [...$tx.proposals, { deposit: '100000000000', returnCredential: `key:${WALLETS[0].keyHash}`, actionType: 'info' }])}>
      {#each $tx.proposals as p, i}
        <div class="line">
          <span class="key">#{i}</span>
          <select bind:value={p.actionType}>
            <option value="info">info</option><option value="treasuryWithdrawals">treasury withdrawals</option>
            <option value="parameterChange">parameter change</option><option value="noConfidence">no confidence</option>
          </select>
          <input type="text" class="qty" bind:value={p.deposit} title="Deposit (lovelace)" />
          <button type="button" class="remove" on:click={() => ($tx.proposals = remove($tx.proposals, i))}>×</button>
        </div>
        <div class="line sub"><span class="key">Return</span><CredentialField bind:value={p.returnCredential} /></div>
        {#if p.actionType === 'treasuryWithdrawals' || p.actionType === 'parameterChange'}
          <div class="line sub"><span class="key">Guardrail</span><input type="text" class="mono grow" bind:value={p.guardrail} placeholder="$self · script hash · none" /></div>
        {/if}
      {/each}
      {#if purpose === 'propose'}
        <div class="line"><span class="key">Script</span><select bind:value={$tx.purpose.index}>{#each $tx.proposals as _, i}<option value={i}>proposal #{i}</option>{/each}</select></div>
      {/if}
    </Section>
  {/if}

  {#if showDatums}
    <Section title="Datum witnesses" count={$tx.datums.length} open onAdd={() => ($tx.datums = [...$tx.datums, { format: 'uplc', value: 'Constr 0 []' }])}>
      {#each $tx.datums as d, i}
        <div class="line"><div class="grow"><DataField bind:value={$tx.datums[i]} compact /></div><button type="button" class="remove" on:click={() => ($tx.datums = remove($tx.datums, i))}>×</button></div>
      {/each}
    </Section>
  {/if}

  {#if showTreasury}
    <Section title="Treasury" open>
      <div class="line"><span class="key">Current</span><input type="text" class="mono" bind:value={$tx.currentTreasuryAmount} placeholder="none" /></div>
      <div class="line"><span class="key">Donation</span><input type="text" class="mono" bind:value={$tx.treasuryDonation} placeholder="none" /></div>
    </Section>
  {/if}

  <div class="more">
    <button type="button" class="link" on:click={() => (moreOpen = !moreOpen)}>{moreOpen ? '− Fewer fields' : '+ More fields'}</button>
    {#if moreOpen}
      <div class="chips">
        {#each [['withdrawals', 'Withdrawals'], ['certificates', 'Certificates'], ['votes', 'Votes'], ['proposals', 'Proposals'], ['datums', 'Datum witnesses'], ['treasury', 'Treasury']] as [key, name]}
          <button type="button" class="wallet" class:on={extra[key]} on:click={() => (extra = { ...extra, [key]: !extra[key] })}>{name}</button>
        {/each}
        <label class="txid">Tx id <input type="text" class="mono" bind:value={$tx.txId} /></label>
      </div>
    {/if}
  </div>
</div>

<style>
  .mock-tx { display: flex; flex-direction: column; font-size: 12px; }
  .purpose { display: grid; grid-template-columns: repeat(6, 1fr); background: var(--bg-secondary); border: 1px solid var(--border); border-radius: 7px; padding: 2px; gap: 2px; margin-bottom: 10px; }
  .purpose button { padding: 5px 0; font-size: 12px; background: transparent; color: var(--text-secondary); border-radius: 5px; }
  .purpose button.active { background: var(--accent); color: var(--bg-primary); font-weight: 600; }
  .essentials { display: flex; flex-direction: column; gap: 8px; padding-bottom: 10px; border-bottom: 1px solid var(--border); }
  .spent { display: flex; align-items: center; gap: 8px; }
  .spent select { flex: 1; font-size: 12px; }
  .label { font-size: 11px; font-weight: 600; color: var(--text-secondary); min-width: 64px; }
  .hint, .warn { margin: 0; font-size: 11px; color: var(--text-muted); }
  .warn { color: var(--warning); }
  .line { display: flex; align-items: center; gap: 6px; }
  .line.sub { padding-left: 20px; }
  .key { width: 56px; flex-shrink: 0; color: var(--text-muted); font-size: 11px; }
  .grow { flex: 1; min-width: 0; }
  .mono { font-family: var(--font-mono); }
  .qty { width: 90px; }
  .index { width: 60px; }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
  .wallet { font-size: 11px; padding: 3px 10px; border-radius: 12px; background: var(--bg-surface); color: var(--text-secondary); }
  .wallet.on { background: var(--success); color: var(--bg-primary); }
  .remove { background: transparent; color: var(--text-muted); padding: 0 8px; font-size: 15px; }
  .remove:hover { color: var(--error); }
  .link { background: transparent; color: var(--accent); font-size: 11px; padding: 2px 4px; }
  .more { padding: 8px 4px; display: flex; flex-direction: column; gap: 6px; }
  .txid { display: flex; gap: 6px; align-items: center; font-size: 11px; color: var(--text-muted); }
  input[type="text"], input[type="number"], select { font-size: 12px; padding: 3px 6px; }
</style>
