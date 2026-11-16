// @julclang/julc-vm-wasm (ADR-077 D2, D5): createJulcVm, and the types of @julclang/wasm-client, which this package
// depends on at its own exact version.
import type {PackageOptions, VmPackageClient} from '@julclang/wasm-client';

export type * from '@julclang/wasm-client';

/** What the caller may set: the engine's files, manifest and version are this package's (engine.js). */
export type EngineOptions = Omit<PackageOptions, 'launcherUrl' | 'moduleUrl' | 'manifest' | 'expectedVersion' | 'rest'
  | 'baseUrl'>;

/** Create a client of JuLC's VM engine, running in its own worker; dispose() ends it. */
export function createJulcVm(options?: EngineOptions): Promise<VmPackageClient>;
