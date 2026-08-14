# ADR-017 P.4 evidence

This fixture closes the ecosystem and runtime evidence for purpose-indexed
`@MultiValidator` blueprints.

Run:

```bash
verification/p4/scripts/verify.sh
```

The driver:

- runs compiler VM tests that execute both dispatch branches, reject malformed
  boundaries, and prove one purpose cannot reach another handler;
- generates and validates the mixed `SPEND`/`MINT`/`CERTIFY` blueprint through
  JuLC's normal build path, including the standard `CERTIFY` to `publish`
  mapping;
- exercises the CLI, Gradle plugin, annotation processor, and Playground
  publication paths;
- loads the compatibility fixture through cardano-client-lib's concrete CIP-57
  loader and constructs both purpose-local values with `PlutusDataAdapter`;
- generates separate spending and minting Lean workspaces from the same script;
  and
- asserts that both manifests bind byte-identical compiled code and the same
  Cardano script hash while recording different exact blueprint entry titles.

The generated workspaces intentionally retain the generic, unspecialized
security property. This milestone proves interface/artifact identity, not a
contract-specific security theorem.

`CERTIFY` is published as CIP-57 `publish`. `VOTE`, `PROPOSE`, and manual
dispatch are negative publication cases and remain fail-closed as described by
ADR-017.
