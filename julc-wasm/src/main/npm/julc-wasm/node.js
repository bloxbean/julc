// @julclang/julc-wasm in Node.js (ADR-077 D3). The engine is the same: index.js imports @julclang/wasm-client, which
// Node.js and server bundlers resolve to the client's Node.js bridge (its "node" condition), so each client runs the
// engine in its own worker_threads worker. The caller passes no flags.
export {createJulc} from './index.js';
