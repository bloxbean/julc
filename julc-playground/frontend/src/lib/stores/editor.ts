import { writable } from 'svelte/store';
import type {
  CheckResponse,
  CompileResponse,
  Diagnostic,
  EvaluateResponse,
  FieldInfo,
  VariantInfo,
} from '../api/client';

const DEFAULT_JAVA_SOURCE = `import java.math.BigInteger;

@Validator
class SimpleValidator {
    static boolean isPositive(BigInteger x) {
        return x > 0;
    }

    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return isPositive(42);
    }
}
`;

export const source = writable(DEFAULT_JAVA_SOURCE);
export const librarySource = writable<string>('');
export const editorTab = writable<'validator' | 'library'>('validator');

export const diagnostics = writable<Diagnostic[]>([]);
export const checkResult = writable<CheckResponse | null>(null);
export const pirText = writable<string | null>(null);
export const uplcText = writable<string | null>(null);
export const blueprintJson = writable<string | null>(null);
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
