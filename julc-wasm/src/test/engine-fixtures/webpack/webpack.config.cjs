// webpack 5, production, with no configuration of its own: Terser minifies whatever it emits, the launcher included.
const path = require('node:path');
module.exports = {mode: 'production', entry: './browser/app.js',
  output: {path: path.resolve(__dirname, '../dist/webpack'), clean: true}};
