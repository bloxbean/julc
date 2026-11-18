// A local npm registry for the tests of the release workflow's npm scripts (ADR-077 M4): enough of the registry
// protocol for `npm publish`, `npm pack name@version` and `npm install`, plus what the tests need to arrange:
// - versions seeded directly, with or without a provenance attestation;
// - provenance attached to every version published through it, as npm attaches it under trusted publishing (the
//   bundle is not signed: the publish job reads the statement only, and the signature is npm-verify's to check);
// - a version that exists but is hidden until a publication of it is attempted (registry lag), which then answers
//   npm's "cannot publish over the previously published versions";
// - a publication that fails with HTTP 500.
// Every publication attempt is logged.
import {createHash} from 'node:crypto';
import http from 'node:http';

export const SLSA = 'https://slsa.dev/provenance/v1';

/** An unsigned provenance statement in npm's form, for `name@version` with these tarball bytes. */
export function provenanceDocument(name, version, bytes, {repository, workflow, ref}) {
  const statement = {
    _type: 'https://in-toto.io/Statement/v1',
    subject: [{name: `pkg:npm/${name.replace('@', '%40')}@${version}`,
      digest: {sha512: createHash('sha512').update(bytes).digest('hex')}}],
    predicateType: SLSA,
    predicate: {
      buildDefinition: {buildType: 'https://slsa-framework.github.io/github-actions-buildtypes/workflow/v1',
        externalParameters: {workflow: {ref, repository: `https://github.com/${repository}`, path: workflow}}},
      runDetails: {builder: {id: 'https://github.com/actions/runner/github-hosted'}},
    },
  };
  return {attestations: [{predicateType: SLSA, bundle: {
    mediaType: 'application/vnd.dev.sigstore.bundle.v0.3+json',
    verificationMaterial: {},
    dsseEnvelope: {payloadType: 'application/vnd.in-toto+json',
      payload: Buffer.from(JSON.stringify(statement)).toString('base64'), signatures: []},
  }}]};
}

export async function startRegistry({provenanceOnPublish = null} = {}) {
  // What npm attaches to a publication: settable between publications (each release's own workflow run).
  const settings = {provenanceOnPublish};
  const packages = new Map();
  const attestations = new Map();
  const hidden = new Set();
  const failPublish = new Set();
  // A package's document answered with this raw body (HTTP 200), or {status, body}, instead: a broken registry answer.
  const overrides = new Map();
  const publishes = [];
  let url;

  const escaped = name => name.replace('/', '%2f');
  const entry = name => {
    if (!packages.has(name)) packages.set(name, {versions: {}, 'dist-tags': {}, tarballs: new Map()});
    return packages.get(name);
  };
  /** Stores a version as the registry would, setting `tag` (and `latest`, for a package's first version). */
  const store = (name, version, bytes, tag, {provenance = null} = {}) => {
    const pkg = entry(name);
    const file = `${name.split('/').pop()}-${version}.tgz`;
    const key = `${name}@${version}`;
    pkg.tarballs.set(file, bytes);
    pkg.versions[version] = {name, version, dist: {
      integrity: 'sha512-' + createHash('sha512').update(bytes).digest('base64'),
      shasum: createHash('sha1').update(bytes).digest('hex'),
      tarball: `${url}${escaped(name)}/-/${file}`,
      ...(provenance ? {attestations: {url: `${url}-/npm/v1/attestations/${escaped(name)}@${version}`,
        provenance: {predicateType: SLSA}}} : {}),
    }};
    if (provenance) attestations.set(key, provenanceDocument(name, version, bytes, provenance));
    pkg['dist-tags'][tag] = version;
    pkg['dist-tags'].latest ??= version;
  };

  const send = (res, status, body) => {
    res.writeHead(status, {'content-type': 'application/json'});
    res.end(JSON.stringify(body));
  };
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const route = decodeURIComponent(new URL(req.url, url).pathname.slice(1));
    try {
      if (req.method === 'GET' && route.startsWith('-/npm/v1/attestations/')) {
        const document = attestations.get(route.slice('-/npm/v1/attestations/'.length));
        return document ? send(res, 200, document) : send(res, 404, {error: 'not found'});
      }
      const tarball = /^(.+)\/-\/([^/]+\.tgz)$/.exec(route);
      if (req.method === 'GET' && tarball) {
        const bytes = packages.get(tarball[1])?.tarballs.get(tarball[2]);
        if (!bytes) return send(res, 404, {error: 'not found'});
        res.writeHead(200, {'content-type': 'application/octet-stream'});
        return res.end(bytes);
      }
      if (req.method === 'GET' && overrides.has(route)) {
        const override = overrides.get(route);
        const {status = 200, body} = typeof override === 'string' ? {body: override} : override;
        res.writeHead(status, {'content-type': 'application/json'});
        return res.end(body);
      }
      if (req.method === 'GET') {
        const pkg = packages.get(route);
        if (!pkg) return send(res, 404, {error: 'not found'});
        const versions = Object.fromEntries(Object.entries(pkg.versions)
          .filter(([version]) => !hidden.has(`${route}@${version}`)));
        if (!Object.keys(versions).length) return send(res, 404, {error: 'not found'});
        return send(res, 200, {name: route, 'dist-tags': pkg['dist-tags'], versions});
      }
      if (req.method === 'PUT') {
        if (!/^Bearer .+/.test(req.headers.authorization ?? '')) return send(res, 401, {error: 'authentication required'});
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        const [version] = Object.keys(body.versions);
        const key = `${route}@${version}`;
        publishes.push(key);
        if (failPublish.has(route)) return send(res, 500, {error: 'internal server error (injected)'});
        hidden.delete(key);
        if (packages.get(route)?.versions[version]) {
          return send(res, 403, {error: `You cannot publish over the previously published versions: ${version}.`});
        }
        const [attachment] = Object.values(body._attachments);
        const bytes = Buffer.from(attachment.data, 'base64');
        const declared = body.versions[version].dist;
        if (declared.integrity !== 'sha512-' + createHash('sha512').update(bytes).digest('base64')) {
          return send(res, 400, {error: 'integrity mismatch'});
        }
        const [tag] = Object.keys(body['dist-tags']);
        store(route, version, bytes, tag, {provenance: settings.provenanceOnPublish});
        return send(res, 201, {ok: true});
      }
      send(res, 405, {error: 'method not allowed'});
    } catch (error) {
      send(res, 500, {error: String(error)});
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  url = `http://127.0.0.1:${server.address().port}/`;
  return {
    url, packages, publishes, failPublish, overrides,
    get provenanceOnPublish() { return settings.provenanceOnPublish; },
    set provenanceOnPublish(value) { settings.provenanceOnPublish = value; },
    /** A version already on the registry, for example from an earlier attempt or published by hand. */
    seed: (name, version, bytes, {tag = 'latest', provenance = null, hide = false} = {}) => {
      store(name, version, bytes, tag, {provenance});
      if (hide) hidden.add(`${name}@${version}`);
    },
    close: () => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }),
  };
}
