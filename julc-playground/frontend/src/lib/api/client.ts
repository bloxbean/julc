const BASE = import.meta.env.VITE_API_URL || '';

async function post<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
  if (res.status === 408) throw new Error('Request timed out. Please try with simpler code.');
  if (res.status === 429) throw new Error('Rate limit exceeded. Please slow down.');
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
  return res.json();
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
  return res.json();
}

export interface Diagnostic {
  level: string;
  code: string;
  message: string;
  startLine: number | null;
  startCol: number | null;
  endLine: number | null;
  endCol: number | null;
  suggestion: string | null;
}

export interface FieldInfo {
  name: string;
  type: string;
}

export interface VariantInfo {
  name: string;
  tag: number;
  fields: FieldInfo[];
}

export interface CheckResponse {
  valid: boolean;
  contractName: string | null;
  purpose: string | null;
  params: FieldInfo[];
  datumName: string | null;
  datumFields: FieldInfo[];
  redeemerVariants: VariantInfo[];
  redeemerFields: FieldInfo[];
  diagnostics: Diagnostic[];
}

export interface CompileResponse {
  uplcText: string | null;
  javaSource: string | null;
  pirText: string | null;
  blueprintJson: string | null;
  compiledCode: string | null;
  scriptHash: string | null;
  scriptSizeBytes: number;
  scriptSizeFormatted: string | null;
  params: FieldInfo[];
  diagnostics: Diagnostic[];
}

export interface EvaluateResponse {
  success: boolean;
  budgetCpu: number;
  budgetMem: number;
  traces: string[];
  error: string | null;
  diagnostics: Diagnostic[];
}

export interface EvalExpressionResponse {
  success: boolean;
  result: string | null;
  type: string | null;
  budgetCpu: number;
  budgetMem: number;
  traces: string[];
  error: string | null;
  uplc: string | null;
}

export interface ExampleItem {
  name: string;
  source: string;
  language: string;
}

export interface ScenarioItem {
  name: string;
  description: string;
  signers: string[];
  validRangeAfter: number | null;
  validRangeBefore: number | null;
  datum: Record<string, string>;
  redeemerFields: Record<string, string>;
  redeemerVariant: number | null;
}

export const api = {
  check: (source: string, signal?: AbortSignal) =>
    post<CheckResponse>('/api/check', { source }, signal),
  compile: (source: string, librarySource?: string) =>
    post<CompileResponse>('/api/compile', { source, librarySource: librarySource || undefined }),
  evaluate: (body: {
    source: string;
    librarySource?: string;
    paramValues?: Record<string, string>;
    scenario?: { signers?: string[]; validRangeAfter?: number; validRangeBefore?: number };
    datum?: Record<string, string>;
    redeemer?: { variant: number; fields?: Record<string, string> };
  }) => post<EvaluateResponse>('/api/evaluate', body),
  examples: () => get<ExampleItem[]>('/api/examples'),
  example: (name: string) => get<ExampleItem>(`/api/examples/${name}`),
  evalExpression: (expression: string) =>
    post<EvalExpressionResponse>('/api/eval', { expression }),
  scenarios: (purpose: string) =>
    get<{ purpose: string; scenarios: ScenarioItem[] }>(`/api/scenarios/${purpose}`),
};
