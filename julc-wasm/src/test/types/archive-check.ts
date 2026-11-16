// The archives' and the julc-wasm jar's declarations (rest: true by default, ADR-064's EngineClient).
import {createJulc, type FullClient, type VmClient, type VmPackageClient, type FullPackageClient,
  type UplcModels_DecodeRequest, type Operations, type Options} from './archive/julc-wasm.js';

declare const flag: boolean;
declare const options: Options;

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
function expectType<T extends true>(): void {}

export async function check(): Promise<void> {
  const omitted = await createJulc({baseUrl: '/wasm/'});
  expectType<Equal<typeof omitted, VmClient | FullClient>>();
  void omitted.rest('GET', '/api/health');
  // rest known only at run time, or options typed as the exported union: rest() only after a check.
  const unknown = await createJulc({baseUrl: '/wasm/', rest: flag});
  expectType<Equal<typeof unknown, VmClient | FullClient | VmPackageClient | FullPackageClient>>();
  // @ts-expect-error: not every client of that union has rest()
  void unknown.rest('GET', '/api/health');
  if ('rest' in unknown) void unknown.rest('GET', '/api/health');
  const fromUnion = await createJulc(options);
  expectType<Equal<typeof fromUnion, VmClient | FullClient | VmPackageClient | FullPackageClient>>();
  const withRest = await createJulc({baseUrl: '/wasm/', rest: true});
  expectType<Equal<typeof withRest, VmClient | FullClient>>();
  const without = await createJulc({baseUrl: '/wasm/', rest: false});
  expectType<Equal<typeof without, VmPackageClient | FullPackageClient>>();
  // @ts-expect-error: no REST adapter when it is turned off
  void without.rest;
  // An embedder names its client type (ADR-064).
  type Elci = VmClient & {elci: Operations<{compile(request: {module: string}): {ok: boolean}}>};
  const elci = await createJulc<Elci>({baseUrl: '/elci/', expectedVersion: '1.0.0'});
  const compiled: {ok: boolean} = await elci.elci.compile({module: 'Main'});
  const decode: UplcModels_DecodeRequest | null = null;
  void [compiled, decode];
}
