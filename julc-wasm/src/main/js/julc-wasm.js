/* The browser entry, and the archives' client (createJulc, isFullClient). The worker is constructed in the one form
 * Vite and webpack recognise, so a bundle ships it (ADR-077 D2). */
// The archive build (build.gradle, singleFileClient) removes this import and puts julc-client.js in this file.
import {startClient, isFullClient} from './julc-client.js';

export {isFullClient};
const browser = {
  packageWorker: () => new Worker(new URL('./julc-worker.js', import.meta.url)),
  archiveWorker: url => new Worker(url),
  readJson: async url => {
    const response = await fetch(url);
    if (!response.ok) throw new Error('Cannot read ' + url.href + ': HTTP ' + response.status);
    return response.json();
  },
};
/** Create an isolated Promise-based client of a JuLC engine, or of one built on julc-wasm. Each owns one worker. */
export function createJulc(options = {}) {
  return startClient(options, browser);
}
