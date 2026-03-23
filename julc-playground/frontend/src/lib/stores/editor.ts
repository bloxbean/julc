import { writable } from 'svelte/store';
import type {
  CheckResponse,
  CompileResponse,
  Diagnostic,
  EvaluateResponse,
  FieldInfo,
  VariantInfo,
} from '../api/client';

export const source = writable(`contract "SimpleTransfer" version "1.0" purpose spending
params:
    receiver : PubKeyHash
rule "Receiver can spend"
when
    Transaction( signedBy: receiver )
then allow
default: deny
`);

export const diagnostics = writable<Diagnostic[]>([]);
export const checkResult = writable<CheckResponse | null>(null);
export const javaSource = writable<string | null>(null);
export const uplcText = writable<string | null>(null);
export const compileResult = writable<CompileResponse | null>(null);
export const evalResult = writable<EvaluateResponse | null>(null);

export const isChecking = writable(false);
export const isCompiling = writable(false);
export const isEvaluating = writable(false);

// Derived metadata from check
export const contractName = writable<string | null>(null);
export const purpose = writable<string | null>(null);
export const params = writable<FieldInfo[]>([]);
export const datumName = writable<string | null>(null);
export const datumFields = writable<FieldInfo[]>([]);
export const redeemerVariants = writable<VariantInfo[]>([]);
export const redeemerFields = writable<FieldInfo[]>([]);

export function updateFromCheck(res: CheckResponse) {
  checkResult.set(res);
  diagnostics.set(res.diagnostics);
  contractName.set(res.contractName);
  purpose.set(res.purpose);
  params.set(res.params);
  datumName.set(res.datumName);
  datumFields.set(res.datumFields);
  redeemerVariants.set(res.redeemerVariants);
  redeemerFields.set(res.redeemerFields);
}
