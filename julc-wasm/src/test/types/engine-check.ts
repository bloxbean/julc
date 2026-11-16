// The engine packages' declarations from their tarballs (ADR-077 D2, D5, M3): each entry function returns its
// variant's package client, takes only the options the package leaves to the caller, and re-exports the client's
// types; ./engine names the engine's files.
import {
  createJulcVm, type EngineOptions, type VmPackageClient, type ScriptInput, type Manifest,
  type Input,
} from '@julclang/julc-vm-wasm';
import {createJulc, type FullPackageClient, type CompileRequest, type WorkerLike} from '@julclang/julc-wasm';
import {manifest, launcherUrl, moduleUrl, version, engineFiles} from '@julclang/julc-vm-wasm/engine';
import * as fullEngine from '@julclang/julc-wasm/engine';

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
function expectType<T extends true>(): void {}
declare const worker: WorkerLike;

export async function engines(): Promise<void> {
  const vm = await createJulcVm();
  expectType<Equal<typeof vm, VmPackageClient>>();
  // @ts-expect-error: no REST adapter in package mode
  void vm.rest;
  // @ts-expect-error: the VM engine has no compiler
  void vm.compiler;
  const script: Input<ScriptInput> = {script: '46450101002499'};
  void vm.vm.applyParams({language: 'V3', script, params: [{int: 42n}]});
  const options: EngineOptions = {timeoutMs: 1000, loadTimeoutMs: 60000, workerFactory: () => worker};
  const full = await createJulc(options);
  expectType<Equal<typeof full, FullPackageClient>>();
  const compile: Input<CompileRequest> = {source: 'class A {}'};
  void full.compiler.compile(compile);
  // @ts-expect-error: the package names its own engine files
  await createJulcVm({launcherUrl: 'a.js'});
  // @ts-expect-error: and its own version
  await createJulc({expectedVersion: '1'});
  // @ts-expect-error: no REST adapter in package mode
  await createJulcVm({rest: true});
  const files: [Manifest, URL, URL, string] = [manifest, launcherUrl, moduleUrl, version];
  const fullFiles: [Manifest, URL, URL, string] = [fullEngine.manifest, fullEngine.launcherUrl, fullEngine.moduleUrl,
    fullEngine.version];
  // @ts-expect-error: the exported manifest is frozen
  manifest.variant = 'full';
  const copy = engineFiles();
  copy.manifest.variant = 'full';
  expectType<Equal<typeof copy.version, typeof version>>();
  void files;
  void fullFiles;
}
