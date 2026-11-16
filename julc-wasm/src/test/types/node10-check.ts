// TypeScript's node10 resolution ignores the exports map: the root through types, ./models through typesVersions.
import type {VmClient, ScriptInput} from '@julclang/wasm-client';
import type {UplcModels_ScriptInput} from '@julclang/wasm-client/models';

export const input: ScriptInput | null = null as UplcModels_ScriptInput | null;
export type Client = VmClient;
