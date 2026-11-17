// The Chrome extension: the browser application built with relative URLs, with the extension's manifest and
// service worker copied beside it.
export default {root: 'browser', base: './', publicDir: '../extension', logLevel: 'warn',
  build: {outDir: '../dist/extension', emptyOutDir: true}};
