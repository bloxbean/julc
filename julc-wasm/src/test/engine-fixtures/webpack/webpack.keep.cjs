// webpack 5, production, with the README's configuration: the engine files are emitted as assets and kept out of
// minification, so the launcher keeps its bytes.
const path = require('node:path');
const TerserPlugin = require('terser-webpack-plugin');
module.exports = {mode: 'production', entry: './browser/app.js',
  output: {path: path.resolve(__dirname, '../dist/webpack-keep'), clean: true},
  module: {rules: [{test: /[\\/]@julclang[\\/][^\\/]+[\\/]engine[\\/]/, type: 'asset/resource',
    generator: {filename: 'engine/[name]-[contenthash:8][ext]'}}]},
  optimization: {minimizer: [new TerserPlugin({exclude: /^engine\//})]}};
