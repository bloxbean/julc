# Node-diversity uplc-conformance corpus (vendored)

The vectors of the Cardano node-diversity `uplc-conformance` scenario, kept here byte for byte so that
`./gradlew build` and `./gradlew :julc-vm-java:ndcUplcConformance` run them without a network (as Amaru keeps its
copy under `crates/amaru-uplc/tests/conformance/`). `UplcConformanceNdcScenario` reads them from the test classpath.

| Path | What | Source | SHA-256 |
|---|---|---|---|
| `corpus/` | the scenario's tarball, unpacked: `cost-models/`, `protocol-version-10/`, `protocol-version-11/` (5,011 files) | `https://pub-2239d82d9a074482b2eb2c886191cb4e.r2.dev/uplc-conformance.tar.zst`, linked from the scenario page | tarball `8bd4ad4f9f4c8c6b8ffa1f06ed1db0b43043afbba77ef6a14bcfb5a52345ed3f`; tree `6e46fc745502b36b3928eb8d38d9ac1d82800eba57bafa8fcb089faaec588a7f` |
| `expectations.json` | what the site scores a report against | [`scenarios/uplc-conformance/expectations.json`](https://github.com/pragma-org/node-diversity-conformance/blob/e02a9e1c7dc15cc6d618c7ffdbd41167a38de2a4/scenarios/uplc-conformance/expectations.json) | `1c93d210b4bf155b1d53fecbb98c380a35b647ea3738efffa674e3b348001357` |
| `LICENSE` | Apache License 2.0 of the node-diversity repository | [`LICENSE`](https://github.com/pragma-org/node-diversity-conformance/blob/e02a9e1c7dc15cc6d618c7ffdbd41167a38de2a4/LICENSE) | |

- **Upstream:** <https://github.com/pragma-org/node-diversity-conformance> at revision
  `e02a9e1c7dc15cc6d618c7ffdbd41167a38de2a4`, whose `scenarios/uplc-conformance/README.md` links the tarball.
  The vectors derive from the Plutus conformance tests (<https://github.com/IntersectMBO/plutus>, Apache-2.0) and
  regression vectors of the node-diversity project. Licensed under the Apache License 2.0 (`LICENSE`); not modified.
- **Tree digest:** the SHA-256 of the `shasum -a 256` listing of every file under `corpus/`, sorted by path:

  ```bash
  cd corpus && find . -type f | LC_ALL=C sort | sed 's|^\./||' | tr '\n' '\0' | xargs -0 shasum -a 256 | shasum -a 256
  ```

  `UplcConformanceCorpusTest` (part of `./gradlew build`) checks the tree digest and the digest of
  `expectations.json`, so an edit, a lost file or a line-ending rewrite fails the build. `.gitattributes` turns off
  Git's text conversion here.

## Refreshing

A corpus upgrade is deliberate: run both scenarios and review every difference.

```bash
D=julc-vm-java/src/test/resources/ndc/uplc-conformance
REV=<node-diversity-conformance revision>
curl -fLo /tmp/uplc-conformance.tar.zst https://pub-2239d82d9a074482b2eb2c886191cb4e.r2.dev/uplc-conformance.tar.zst
shasum -a 256 /tmp/uplc-conformance.tar.zst            # record it below and in the table
rm -rf "$D/corpus" && mkdir "$D/corpus" && zstd -dc /tmp/uplc-conformance.tar.zst | tar -x -C "$D/corpus"
curl -fLo "$D/expectations.json" \
  "https://raw.githubusercontent.com/pragma-org/node-diversity-conformance/$REV/scenarios/uplc-conformance/expectations.json"
curl -fLo "$D/LICENSE" "https://raw.githubusercontent.com/pragma-org/node-diversity-conformance/$REV/LICENSE"
```

Then update the digests in this file, `UplcConformanceCorpusTest` and `NdcCorpus.NDC_REVISION` (the turbo reference
reports come from the same revision), and run `./gradlew :julc-vm-java:ndcUplcConformance`.
