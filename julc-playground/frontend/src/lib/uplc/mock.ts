import type { DataInput, MockTransaction, PurposeType, TxIn, TxOut, Value } from './types';

/** Demo wallets with recognizable key hashes, so the mock transaction reads like a story. */
export const WALLETS = [
  { name: 'Alice', keyHash: 'a11ce'.padEnd(56, '0') },
  { name: 'Bob', keyHash: 'b0b'.padEnd(56, '0') },
  { name: 'Carol', keyHash: 'ca201'.padEnd(56, '0') },
] as const;

export const SELF = '$self';
export const ADA = 1_000_000n;

const ALICE = WALLETS[0].keyHash;
const BOB = WALLETS[1].keyHash;

export const PURPOSES: { type: PurposeType; label: string; hint: string }[] = [
  { type: 'spend', label: 'Spend', hint: 'The script guards an output being spent' },
  { type: 'mint', label: 'Mint', hint: 'The script is a minting policy' },
  { type: 'reward', label: 'Reward', hint: 'The script is a stake credential withdrawing rewards' },
  { type: 'certify', label: 'Certify', hint: 'The script authorizes a certificate' },
  { type: 'vote', label: 'Vote', hint: 'The script is a governance voter' },
  { type: 'propose', label: 'Propose', hint: 'The script is a proposal guardrail' },
];

export const data = (value: string, format: DataInput['format'] = 'auto'): DataInput => ({ format, value });
export const ada = (n: number | bigint): Value => ({ lovelace: (BigInt(n) * ADA).toString() });
const txId = (seed: string) => seed.padEnd(64, '0');

function walletInput(index: number): TxIn {
  return { txId: txId('feed'), index, address: { payment: `key:${ALICE}` }, value: ada(100), datum: { kind: 'none' } };
}

function change(lovelace: bigint, assets: Value['assets'] = []): TxOut {
  return { address: { payment: `key:${ALICE}` }, value: { lovelace: lovelace.toString(), assets }, datum: { kind: 'none' } };
}

function base(type: PurposeType): MockTransaction {
  return {
    purpose: { type, index: 0, target: SELF, voterType: 'drep' },
    redeemer: data('Constr 0 []', 'uplc'),
    inputs: [walletInput(1)],
    referenceInputs: [],
    outputs: [change(100n * ADA - 200_000n)],
    fee: '200000',
    mint: [],
    certificates: [],
    withdrawals: [],
    validRange: { from: null, to: null, fromInclusive: true, toInclusive: true },
    signatories: [],
    datums: [],
    txId: txId('7ac0'),
    votes: [],
    proposals: [],
  };
}

/** A complete, valid-looking transaction for a purpose; users only edit what their script checks. */
export function defaultTransaction(type: PurposeType): MockTransaction {
  const tx = base(type);
  switch (type) {
    case 'spend':
      tx.inputs = [
        { txId: txId('c0ffee'), index: 0, address: { payment: SELF }, value: ada(10), datum: { kind: 'inline', data: data('Constr 0 []', 'uplc') } },
        walletInput(1),
      ];
      tx.outputs = [change(110n * ADA - 200_000n)];
      break;
    case 'mint':
      tx.mint = [{ policyId: SELF, tokenName: '746f6b656e', quantity: '1' }];
      tx.outputs = [change(100n * ADA - 200_000n, [{ policyId: SELF, tokenName: '746f6b656e', quantity: '1' }])];
      break;
    case 'reward':
      tx.withdrawals = [{ credential: SELF, amount: '0' }];
      break;
    case 'certify':
      tx.certificates = [{ type: 'regStaking', credential: SELF, deposit: '2000000' }];
      tx.outputs = [change(98n * ADA - 200_000n)];
      break;
    case 'vote':
      tx.votes = [{ voterType: 'drep', voter: SELF, actionTxId: txId('90e1'), actionIndex: 0, vote: 'yes' }];
      break;
    case 'propose':
      tx.proposals = [{
        deposit: '100000000000', returnCredential: `key:${ALICE}`, actionType: 'treasuryWithdrawals', guardrail: SELF,
        withdrawals: [{ credential: `key:${BOB}`, amount: '1000000' }],
      }];
      break;
  }
  return tx;
}

/** Mock purpose for a JuLC contract purpose (SPENDING, MINTING, WITHDRAW, …). */
export function purposeFromContract(purpose: string | null | undefined): PurposeType {
  const p = (purpose ?? '').toUpperCase();
  if (p.startsWith('MINT')) return 'mint';
  if (p.startsWith('WITHDRAW') || p.startsWith('REWARD')) return 'reward';
  if (p.startsWith('CERT')) return 'certify';
  if (p.startsWith('VOT')) return 'vote';
  if (p.startsWith('PROPOS')) return 'propose';
  return 'spend';
}

/** Friendly label for a credential or address payment part. */
export function credentialLabel(value: string | undefined): string {
  if (!value) return '—';
  if (value === SELF) return 'this script';
  const hex = value.replace(/^(key|script):/, '');
  const wallet = WALLETS.find((w) => w.keyHash === hex);
  if (wallet) return wallet.name;
  if (value.startsWith('addr') || value.startsWith('stake')) return value.slice(0, 12) + '…';
  return (value.startsWith('script:') ? 'script ' : 'key ') + hex.slice(0, 8) + '…';
}

export function formatAda(lovelace: string | undefined): string {
  if (!lovelace) return '0 ₳';
  try {
    const l = BigInt(lovelace);
    const whole = l / ADA;
    const frac = (l < 0n ? -l : l) % ADA;
    return frac === 0n ? `${whole} ₳` : `${whole}.${frac.toString().padStart(6, '0').replace(/0+$/, '')} ₳`;
  } catch {
    return `${lovelace} lovelace`;
  }
}

export function formatNumber(n: number): string {
  if (n >= 1_000_000_000) return (n / 1_000_000_000).toFixed(2) + 'B';
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

/** Protocol parameters used for limits and the fee estimate. */
export interface ProtocolPreset { name: string; protocolVersion: number; maxCpu: number; maxMem: number; priceMem: number; priceSteps: number }

export const PRESETS: ProtocolPreset[] = [
  { name: 'Mainnet · PV11', protocolVersion: 11, maxCpu: 10_000_000_000, maxMem: 14_000_000, priceMem: 0.0577, priceSteps: 0.0000721 },
  { name: 'Mainnet · PV10', protocolVersion: 10, maxCpu: 10_000_000_000, maxMem: 14_000_000, priceMem: 0.0577, priceSteps: 0.0000721 },
];

/** Execution part of the script fee in lovelace: ceil(priceMem·mem + priceSteps·cpu). */
export function scriptFee(preset: ProtocolPreset, cpu: number, mem: number): number {
  return Math.ceil(preset.priceMem * mem + preset.priceSteps * cpu);
}
