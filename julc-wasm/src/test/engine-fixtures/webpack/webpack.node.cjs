// webpack 5 with target: 'node', production: a CommonJS server bundle.
const path = require('node:path');
module.exports = {mode: 'production', target: 'node', entry: './server/server.mjs',
  output: {path: path.resolve(__dirname, '../dist/webpack-node-cjs'), filename: 'server.js', clean: true}};
