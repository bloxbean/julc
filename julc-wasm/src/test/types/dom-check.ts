// With the DOM library, a DOM Worker is a WorkerLike.
import {createJulc, type WorkerLike} from '@julclang/wasm-client';

export async function check(): Promise<void> {
  const worker: WorkerLike = new Worker(new URL('./x.js', import.meta.url));
  await createJulc({baseUrl: '/engine/', workerFactory: url => new Worker(url)});
  void worker;
}
