// The recursive dataText of julc-runtime.js before ADR-077 M1 made it iterative: the reference its output and its
// errors are compared with (codec.test.mjs). Not shipped.
const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
function invalid(message) {
  return Object.assign(new Error(message), { code: 'invalid-request', status: 400, body: { error: message } });
}
export function dataText(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid('Expected structured Plutus Data');
  const kinds = ['int', 'bytes', 'list', 'map', 'constructor'].filter(k => own(value, k));
  if (kinds.length !== 1) throw invalid('Data must have exactly one constructor');
  const kind = kinds[0];
  if (Object.keys(value).some(k => k !== kind && !(kind === 'constructor' && k === 'fields'))) {
    throw invalid('Unexpected Data field');
  }
  const array = (items) => {
    if (!Array.isArray(items)) throw invalid('Expected Data array');
    return '[' + items.map(dataText).join(',') + ']';
  };
  switch (kind) {
    case 'int':
      if (typeof value.int !== 'bigint') throw invalid('Data int must be BigInt');
      return '{"int":' + value.int.toString() + '}';
    case 'constructor':
      if (typeof value.constructor !== 'bigint' || value.constructor < 0n || value.constructor >= 1n << 64n) {
        throw invalid('Data constructor must be a BigInt in unsigned Word64 range');
      }
      return '{"constructor":' + value.constructor.toString() + ',"fields":' + array(value.fields) + '}';
    case 'bytes':
      if (typeof value.bytes !== 'string' || !/^(?:[0-9a-fA-F]{2})*$/.test(value.bytes)) throw invalid('Data bytes must be hex');
      return JSON.stringify({ bytes: value.bytes });
    case 'list': return '{"list":' + array(value.list) + '}';
    case 'map':
      if (!Array.isArray(value.map)) throw invalid('Data map must be an entry array');
      return '{"map":[' + value.map.map(e => {
        if (!e || !own(e, 'k') || !own(e, 'v') || Object.keys(e).length !== 2) throw invalid('Expected map entry {k, v}');
        return '{"k":' + dataText(e.k) + ',"v":' + dataText(e.v) + '}';
      }).join(',') + ']}';
  }
}
