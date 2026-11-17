// A function that forwards its own optional options to createJulc (M1-C4), in both distributions: the result is the
// union of client types unless the options say which mode and rest they choose.
import {
  createJulc, type ArchiveOptions, type Options, type PackageOptions, type VmClient, type FullClient,
  type VmPackageClient, type FullPackageClient,
} from '@julclang/wasm-client';
import * as Archive from './archive/julc-wasm.js';

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
function expectType<T extends true>(): void {}
type Result<F extends (...args: any[]) => Promise<unknown>> = Awaited<ReturnType<F>>;

export function load(options?: ArchiveOptions) { return createJulc(options); }
export function loadAny(options?: Options) { return createJulc(options); }
export function loadPackage(options: PackageOptions) { return createJulc(options); }
expectType<Equal<Result<typeof load>, VmClient | FullClient | VmPackageClient | FullPackageClient>>();
expectType<Equal<Result<typeof loadAny>, VmClient | FullClient | VmPackageClient | FullPackageClient>>();
expectType<Equal<Result<typeof loadPackage>, VmPackageClient | FullPackageClient>>();

export function loadArchive(options?: Archive.ArchiveOptions) { return Archive.createJulc(options); }
export function loadArchiveAny(options?: Archive.Options) { return Archive.createJulc(options); }
type ArchiveAll = Archive.VmClient | Archive.FullClient | Archive.VmPackageClient | Archive.FullPackageClient;
expectType<Equal<Result<typeof loadArchive>, ArchiveAll>>();
expectType<Equal<Result<typeof loadArchiveAny>, ArchiveAll>>();
