// webpack 5 with target: 'node', production: an ES-module server bundle.
const path = require('node:path');
module.exports = {mode: 'production', target: 'node', entry: './server/server.mjs', experiments: {outputModule: true},
  output: {path: path.resolve(__dirname, '../dist/webpack-node-esm'), filename: 'server.mjs', module: true,
    chunkFormat: 'module', clean: true}};
