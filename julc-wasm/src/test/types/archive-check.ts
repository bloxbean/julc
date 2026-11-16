// The archives' and the julc-wasm jar's declarations (rest: true by default, ADR-064's EngineClient).
import {createJulc, type FullClient, type VmClient, type VmPackageClient, type FullPackageClient,
  type UplcModels_DecodeRequest, type Operations} from './archive/julc-wasm.js';

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
function expectType<T extends true>(): void {}

export async function check(): Promise<void> {
  const omitted = await createJulc({baseUrl: '/wasm/'});
  expectType<Equal<typeof omitted, VmClient | FullClient>>();
  void omitted.rest('GET', '/api/health');
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
