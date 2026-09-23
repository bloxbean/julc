# JuLC documentation

Static Astro/Starlight site deployed to GitHub Pages by `docs-deploy.yml`.
Use Node **22.19.0 or newer** (the resolved `undici` dependency requires it).
CI uses the latest Node 22 patch. Install the reviewed lockfile, not floating dependencies:

```sh
cd docs
npm ci
npm run build
npm audit
```

`npm run dev` starts the local development server; `npm run preview` previews `dist/`.
Pages live in `src/content/docs/`; sidebar/base URL configuration is in `astro.config.mjs`.
The build can include validators from a sibling `julc-examples` checkout (or
`JULC_EXAMPLES_DIR`); record that checkout when comparing generated catalogs.

## Dependency advisories

The September 2026 release-gate update replaces the vulnerable Astro 6 dependency tree
with Astro 7.3.3, matching Starlight/Svelte integrations, and sharp 0.35.4. A clean
lockfile install and static build passed; `npm audit` reported zero advisories at the
time of validation. This is a point-in-time check, not a guarantee against unknown bugs.

This site has no SSR adapter or deployed Node server. Server-route advisories therefore
have a different exposure than in an SSR application, but build-time image processing
and the local development server still matter. In particular, the published
[AVIF/libheif advisory](https://github.com/withastro/astro/security/advisories/GHSA-26w7-cxv4-gfx2)
can affect optimization of untrusted images; static deployment is not sufficient mitigation.
The patched dependencies are used rather than suppressing audit findings or applying
`--force`/`--legacy-peer-deps`. Do not expose the development server to untrusted networks.

See [release-gate evidence](../adr/evidence/052-pre17-release-gates.md) for versions,
audit totals and validation scope. Future dependency refreshes should use compatible
integrations, a clean `npm ci`, a full site build, and a fresh audit. Follow the repository
security policy for previously undisclosed vulnerabilities.
