// @julclang/wasm-client's declarations from its tarball (ADR-077 D2, D4, D5): the client type follows rest and the
// mode, the plain names are exported, and the generated names stay under ./models.
import {
  createJulc, isFullClient, type Features, type FullClient, type FullPackageClient, type Manifest, type PackageClient,
  type ScriptInput, type DecodeRequest, type TestRunRequest, type TxOut, type VmClient, type VmPackageClient,
  type WorkerLike, type PlutusData, type DataInput, type Evaluation, type Options,
} from '@julclang/wasm-client';
import type {UplcModels_ScriptInput, MockTransaction_TxOut} from '@julclang/wasm-client/models';

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
function expectType<T extends true>(): void {}

const manifest: Manifest = {api: 0, version: 'x', launcher: 'julc-vm-0123456789ab.js', variant: 'vm', julcVersion: '1'};
const worker: WorkerLike = {postMessage() {}, terminate() {}, onmessage: null, onerror: null};
declare const flag: boolean;
declare const options: Options;

export async function check(): Promise<void> {
  const omitted = await createJulc({baseUrl: '/engine/'});
  expectType<Equal<typeof omitted, VmPackageClient | FullPackageClient>>();
  // @ts-expect-error: the package's clients have no REST adapter unless asked for
  void omitted.rest;
  const withRest = await createJulc({baseUrl: '/engine/', rest: true});
  expectType<Equal<typeof withRest, VmClient | FullClient>>();
  void withRest.rest('GET', '/api/health');
  // rest known only at run time, or options typed as the exported union: rest() only after a check.
  const unknown = await createJulc({baseUrl: '/engine/', rest: flag});
  expectType<Equal<typeof unknown, VmClient | FullClient | VmPackageClient | FullPackageClient>>();
  // @ts-expect-error: not every client of that union has rest()
  void unknown.rest('GET', '/api/health');
  if ('rest' in unknown) void unknown.rest('GET', '/api/health');
  const fromUnion = await createJulc(options);
  expectType<Equal<typeof fromUnion, VmClient | FullClient | VmPackageClient | FullPackageClient>>();
  const without = await createJulc({baseUrl: '/engine/', rest: false, workerFactory: () => worker});
  expectType<Equal<typeof without, VmPackageClient | FullPackageClient>>();
  const packaged = await createJulc({launcherUrl: new URL('file:///e/julc-vm.js'), moduleUrl: 'file:///e/julc-vm.js.wasm',
    manifest, expectedVersion: '1'});
  expectType<Equal<typeof packaged, VmPackageClient | FullPackageClient>>();
  // @ts-expect-error: no REST adapter in package mode
  await createJulc({launcherUrl: 'a.js', moduleUrl: 'a.wasm', manifest, rest: true});
  // @ts-expect-error: a client is in one mode
  await createJulc({baseUrl: '/engine/', launcherUrl: 'a.js', moduleUrl: 'a.wasm', manifest});
  // @ts-expect-error: package mode needs the manifest
  await createJulc({launcherUrl: 'a.js', moduleUrl: 'a.wasm'});
  if (isFullClient(packaged)) {
    const checked = await packaged.compiler.check({source: 'class A {}'});
    const valid: boolean = checked.valid;
    void valid;
  }
  const features: Features = packaged.features();
  const julcVersion: string = features.julcVersion;
  const input: ScriptInput = {} as UplcModels_ScriptInput;
  const output: TxOut = {} as MockTransaction_TxOut;
  const request: DecodeRequest = {script: input, protocolVersion: null} as DecodeRequest;
  const decoded = await packaged.vm.decode({script: {script: '46450101002499'}});
  const evaluation: Evaluation = await packaged.vm.evaluate({script: {script: '46450101002499'},
    args: [{int: 1n} satisfies PlutusData, {format: 'cbor', value: '01'} as DataInput]});
  const base: PackageClient = packaged;
  const test: TestRunRequest | null = null;
  void [julcVersion, output, request, decoded, evaluation, base, test];
}
