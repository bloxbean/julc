/* Playground compatibility adapter; the typed engine has no HTTP routes. */
(() => {
  const routes = {
    '/api/check': 'compiler.check', '/api/compile': 'compiler.compile',
    '/api/evaluate': 'compiler.evaluate', '/api/eval': 'compiler.evalExpression',
    '/api/uplc/decode': 'vm.decode', '/api/uplc/decompile': 'uplc.decompile',
    '/api/uplc/evaluate': 'uplc.evaluateTransaction', '/api/uplc/debug': 'uplc.debugTransaction',
    '/api/source-debug/open': 'sourceDebug.open', '/api/source-debug/act': 'sourceDebug.act',
    '/api/source-debug/close': 'sourceDebug.close',
    '/api/source-debug/locals': 'sourceDebug.locals', '/api/source-debug/children': 'sourceDebug.children',
  };
  function typed(value, schema) {
    if (value == null) return value;
    if (schema === 'long' || schema === 'bigint') return BigInt(value);
    if (Array.isArray(schema)) return value.map(v => typed(v, schema[0]));
    if (schema && typeof schema === 'object') return Object.fromEntries(
      Object.entries(value).map(([k,v]) => [k, typed(v, schema.$map ?? schema[k])]));
    return value;
  }
  globalThis.JulcRestAdapter = class {
    constructor(api, catalogue = {}) { this.api = api; this.catalogue = catalogue; this.debug = null; }
    request(method, target, body) {
      const [path, query = ''] = target.split('?');
      const params = new URLSearchParams(query);
      const ok = body => ({status: 200, body});
      try {
        if (method === 'GET') {
          if (path === '/api/health') return ok({status: 'ok', engine: 'wasm'});
          if (path === '/api/examples') {
            const language = params.get('language');
            return ok((this.catalogue.examples || []).filter(e => !language?.trim() || e.language.toLowerCase() === language.toLowerCase()));
          }
          if (path.startsWith('/api/examples/')) {
            const name = decodeURIComponent(path.slice('/api/examples/'.length));
            const example = (this.catalogue.examples || []).find(e => e.name === name);
            return example ? ok(example) : {status: 404, body: {error: 'Example not found: ' + name}};
          }
          if (path.startsWith('/api/scenarios/')) {
            const purpose = decodeURIComponent(path.slice('/api/scenarios/'.length));
            return ok(this.catalogue.scenarios?.[purpose.toUpperCase()] || {
              purpose: purpose.toUpperCase(), scenarios: [], message: 'No scenario templates available for purpose: ' + purpose,
            });
          }
        }
        const operation = method === 'POST' && routes[path];
        if (!operation) return {status: 404, body: {error: 'Endpoint ' + method + ' ' + path + ' not found'}};
        let request = typeof body === 'string' ? JSON.parse(body) : body;
        const [group, name] = operation.split('.');
        let result;
        if (path === '/api/uplc/debug') {
          const {action, step, breakpoints, ...inputs} = request;
          const key = JSON.stringify(inputs);
          if (!this.debug || this.debug.key !== key) {
            if (this.debug) this.api.debug.close({sessionId: this.debug.id});
            const descriptor = this.api.uplc.debugTransaction(typed(inputs, this.api.__schemas[operation]));
            if (!descriptor.ok) return ok(JulcRuntime.legacy(descriptor));
            this.debug = {key, id: descriptor.sessionId};
          }
          result = this.api.debug.act({sessionId: this.debug.id, action, step: step == null ? null : BigInt(step), breakpoints});
        } else {
          result = this.api[group][name](typed(request, this.api.__schemas[operation]));
        }
        if (path.startsWith('/api/source-debug/')) return ok(JulcRuntime.legacy(result));
        const {target: resolvedTarget, costModelId, sessionId, ...rest} = result;
        return ok(JulcRuntime.legacy(rest));
      } catch (error) {
        return {status: error.status || 400, body: JulcRuntime.legacy(error.body || {error: error.message})};
      }
    }
  };
})();
