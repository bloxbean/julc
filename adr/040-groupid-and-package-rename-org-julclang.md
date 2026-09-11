# ADR-040: Rename Maven groupId and Java package to `org.julclang`

- Status: Accepted
- Date: 2026-09-11

## Context / Problem

JuLC currently publishes under Maven groupId `com.bloxbean.cardano` and its
compiler-owned Java package root is `com.bloxbean.cardano.julc`. The CLI and
Playground modules additionally use a second, inconsistent package root,
`com.bloxbean.julc`.

The `org.julclang` namespace has been verified as available/reserved on Maven
Central. JuLC is still in preview (no released 1.0, no backward-compatibility
guarantee — see AGENTS.md "Project Status"), so this is the lowest-cost point
at which to move to its own namespace instead of squatting under the
`com.bloxbean.cardano` prefix used by the unrelated `cardano-client-lib`
project (groupId `com.bloxbean.cardano`, package `com.bloxbean.cardano.client`).

This also removes a standing ambiguity: `com.bloxbean.cardano.julc` reads as
"part of cardano-client-lib" even though JuLC is a separate project that
depends on cardano-client-lib.

## Goals

- Publish all `julc-*` artifacts under Maven groupId `org.julclang`.
- Move the compiler/runtime package root `com.bloxbean.cardano.julc` → `org.julclang`.
- Move the CLI/Playground package root `com.bloxbean.julc` → `org.julclang` (same
  target namespace, for a single consistent package root repo-wide).
- Rename the Gradle plugin id `com.bloxbean.cardano.julc` → `org.julclang.julc`
  (keeps "julc" explicit in what consumers type in `plugins { id '...' }`,
  avoids collapsing to bare `org.julclang`).
- Zero change in generated UPLC / on-chain behavior: hashes, `compiledCode`,
  and budgets for a fixed source input must be byte-identical before and after.
- Update the external `julc-examples` repo and verify it builds/tests clean
  against the republished artifacts.

## Non-goals

- No backward-compatibility shim, no dual-publish under both groupIds.
- Not touching `julc-helloworld`, `julc-cip113`, `julc-cip113-example` in this
  pass (tracked as follow-up work; they will not build against the new
  coordinates until updated).
- Not rewriting historical ADR text under `adr/` — those documents describe
  decisions as true at the time they were written and keep the old package
  name for historical accuracy. This ADR is the record of the switch.
- Not touching `com.bloxbean.cardano.client.*` (cardano-client-lib) —
  different project, different groupId owner, out of scope.

## Current Behavior

- Root `gradle.properties`: `group = com.bloxbean.cardano` (inherited by all
  `julc-*` subprojects; no module overrides it).
- Java package root for library/compiler code: `com.bloxbean.cardano.julc.*`
  (~1176 files / ~5900 occurrences across the repo, `julc-core` through
  `julc-verification`).
- Java package root for `julc-cli` / `julc-playground`: `com.bloxbean.julc.*`
  (~197 files).
- Gradle plugin id: `com.bloxbean.cardano.julc`, declared in
  `julc-gradle-plugin/build.gradle` (`gradlePlugin { plugins { julc { id =
  'com.bloxbean.cardano.julc'; implementationClass = '...JulcPlugin' } } }`).
- SPI `META-INF/services` files whose *filename* is the service interface
  FQCN (must be renamed as files, not just edited): `julc-bls`
  (`com.bloxbean.cardano.julc.core.BlsConstantValidator`), `julc-vm-java`,
  `julc-vm-scalus`, `julc-vm-truffle` (all `com.bloxbean.cardano.julc.vm.JulcVmProvider`).
- Native-image reachability metadata directories keyed by groupId:
  `julc-blueprint/src/main/resources/META-INF/native-image/com.bloxbean.cardano/julc-blueprint/`.
- Compiler-internal FQCN string constants that define the source-level import
  surface JuLC users write against (`ImportResolver.LEDGER_PKG`/`STDLIB_PKG`,
  `TypeResolver`, `StdlibRegistry.PKG`, `JulcCompiler`'s canonical-type
  constants) — these are correctness-critical, not cosmetic: they define what
  package a JuLC validator author must `import` for `Value`, `TxInfo`,
  stdlib libraries, etc.
- Colon-form Maven coordinates (`com.bloxbean.cardano:julc-*`) appear
  separately from the dotted package form, in CLI project scaffolders
  (`GradleProjectScaffolder`, `MavenProjectScaffolder`), docs, and
  `julc-examples`.
- No `startsWith("com.bloxbean...")` or other bare-prefix string filters
  exist in the codebase (verified) — the full `com.bloxbean.cardano.julc`
  prefix is safe to replace wholesale without a segment-boundary collision
  against `com.bloxbean.cardano.client.*`.
- No `module-info.java` files exist (no JPMS module-name concern).

## Decision

1. Maven groupId: `com.bloxbean.cardano` → `org.julclang` (root `gradle.properties`, single source).
2. Package root `com.bloxbean.cardano.julc.*` → `org.julclang.*`.
3. Package root `com.bloxbean.julc.*` (CLI/Playground) → `org.julclang.*` (same target root as #2 — one namespace).
4. Gradle plugin id `com.bloxbean.cardano.julc` → `org.julclang.julc`.
5. Rename (not just edit) the 3 SPI `META-INF/services` files whose filename
   is the FQCN of the service interface, and the
   `META-INF/native-image/com.bloxbean.cardano/` directory in `julc-blueprint`.
6. Update colon-form coordinates (`com.bloxbean.cardano:julc-*` →
   `org.julclang:julc-*`) wherever they appear outside the dotted-package
   pattern (scaffolder templates, docs, `julc-examples`).
7. `adr/` is excluded from the mechanical text replace (kept as historical
   record); this ADR documents the change going forward.
8. `julc-examples` is updated in-place (plugin id, coordinates, version,
   imports) and used as the regression oracle: `plutus.json` `hash` and
   `compiledCode` for every validator must be byte-identical to a pre-rename
   baseline captured before any edit.

## Alternatives Considered

- **Dual-publish under both groupIds during a transition window** — rejected;
  adds real maintenance cost for a preview project with no external SLA and
  no committed backward-compatibility promise (AGENTS.md). Not worth it.
- **Keep plugin id as bare `org.julclang`** (mechanical result of the
  straight rename) — rejected in favor of `org.julclang.julc`, which keeps
  "julc" explicit in the id consumers type into `plugins { id '...' }` and
  avoids a plugin id identical to the groupId namespace.
- **Leave `com.bloxbean.julc` (CLI/Playground) untouched** — rejected;
  leaving two divergent package roots after this change (`org.julclang.*`
  for the library and a lingering `com.bloxbean.julc.*` for tooling) is a
  worse inconsistency than the one being fixed.
- **Rewrite `adr/` history in place** — rejected; ADRs are point-in-time
  decision records per AGENTS.md, and the old package name is part of that
  record's accuracy.

## Affected Modules / Stages

All `julc-*` modules under `settings.gradle` (28 modules): source tree
relocation (`git mv` per package directory), `gradle.properties`, each
module's `build.gradle` where the plugin id or a colon-form coordinate is
referenced, SPI resource files, native-image resource directories, the
annotation processor and its discovery logic (name-based, not
package-prefix-based — verified no risk), CLI project scaffolders and their
generated-project templates, docs (`docs/` content and generator scripts),
and the external `julc-examples` repository.

This is a rename of identifiers only — no compiler stage's *logic* changes.
The risk surface is (a) any place a package/groupId string is data rather
than a Java reference (SPI filenames, native-image dirs, colon-form
coordinates, the CLI's generated-project templates) and (b) whether any
lowering step's output ordering is sensitive to class/package name sort
order (to be verified via the byte-identical `plutus.json` gate, not assumed).

## Compatibility

Breaking, by design (preview status, AGENTS.md explicitly permits this with
no backward-compatibility requirement). No dual-publish, no deprecated
aliases. `julc-helloworld`, `julc-cip113`, `julc-cip113-example` are known to
break until a follow-up updates them (explicitly out of scope for this pass,
per user decision).

## Risks

- **Silent semantic drift disguised as a pure rename**: if any generated-code
  ordering depends on package/class name sort order, hashes could change
  without any test *failing* (tests compare against fixtures generated
  post-rename, not against a pre-rename oracle, unless we explicitly diff
  against a saved baseline). Mitigated by capturing a pre-rename
  `plutus.json` baseline from `julc-examples` and diffing `hash` +
  `compiledCode` byte-for-byte post-rename, per validator.
- **Partial rename leaving stale coordinates**: colon-form Maven coordinates
  and SPI/native-image filenames are not touched by a plain package-dotted
  text replace. Mitigated by an explicit residue audit pass
  (`git grep bloxbean` after the mechanical replace; every remaining hit must
  be explainable — `cardano-client-lib` or a GitHub URL).
- **Stale downstream tooling**: local test skills
  (`julc-test-quick`/`julc-test-julc-examples`/`julc-smoke-*`) hardcode the
  old groupId and will need their own follow-up update; this pass verifies by
  running the underlying commands directly rather than through those skills.

## Implementation Milestones

1. **M1 — Baseline capture.** Build `julc-examples` against current `main`,
   save `plutus.json` (hash + compiledCode per validator) as the regression
   oracle. No repo edits yet.
2. **M2 — Branch + mechanical rename.** New branch. `git mv` all package
   directories (both `com/bloxbean/cardano/julc` and `com/bloxbean/julc`
   trees) to `org/julclang`; rename the 3 SPI files and the native-image
   groupId directory; text-replace dotted-package, colon-coordinate, and
   `group =` occurrences across tracked files (excluding `adr/`); update the
   plugin id declaration. Residue audit.
3. **M3 — In-repo verification.** `compileJava`/`compileTestJava` across all
   modules, then full `./gradlew build`. Fix any residue the audit missed.
4. **M4 — Publish + external verification.** `publishToMavenLocal`; confirm
   `~/.m2/repository/org/julclang/` contents and the plugin marker; update
   `julc-examples` (plugin id, coordinates, version, imports); build/test it;
   diff `plutus.json` against the M1 baseline (byte-identical hash/compiledCode
   required).
5. **M5 — Report.** Milestone summary per CLAUDE.md's completion-report
   format, including what remains stale (sibling repos, local test skills,
   docs site) as explicit follow-ups.

## Verification Strategy

- Build/test gates widen per milestone as instructed by CLAUDE.md: targeted
  module compiles first, then full `./gradlew build`, then the external
  `julc-examples` build/test as the cross-repo oracle.
- The decisive correctness check is **not** "compiles" or "tests pass" (per
  AGENTS.md, insufficient on its own for a compiler-adjacent change) but the
  byte-identical `plutus.json` diff against the pre-rename baseline — this is
  the actual semantic-preservation evidence for a rename that touches
  compiler-internal FQCN constants.

## Verification Evidence (2026-09-11/12)

Executed on branch `rename/org-julclang-groupid-package`, commit `8fb33f4d`.

- **Pre-rename baseline**: published `0.1.0-pre17-fe4dbb9-SNAPSHOT` from
  `main` (`fe4dbb9d`), built `julc-examples` clean against it, captured
  `plutus.json` (58 validator entries, 41 per-validator files) as the oracle.
- **In-repo**: `compileJava compileTestJava` clean across all 28 modules;
  full `./gradlew build` (all tests) green, 10,931 tests / 531 skipped / 0
  failures / 0 errors — skip count matches the last known full-suite baseline
  (ADR-033: 531 skips) exactly, confirming no test suite was silently
  dropped by the directory moves.
- **Docs site**: `generate-catalog.mjs` and `generate-llms-txt.mjs` rerun
  clean (zero stale references, correct `org.julclang` counts); `astro build`
  produces all 32 pages; rendered `dist/` has zero
  `com.bloxbean.cardano.julc` / `com.bloxbean.julc` references.
- **Residue audit**: every remaining `bloxbean` hit outside `adr/` is
  explainable — `cardano-client-lib` (own groupId/package, untouched),
  GitHub URLs (`github.com/bloxbean/julc*`, correctly untouched since the
  GitHub org/repo name is unaffected by the Maven/Java rename), the Homebrew
  tap (`bloxbean/tap/julc`), and Scalus's own `scalus.bloxbean.*` package
  (external dependency). Fixed on discovery: two native-image test path
  assertions (`BlueprintTest`, `CompileControllerTest`) that hardcoded the
  old `META-INF/native-image/com.bloxbean.cardano/` path after the directory
  was renamed; the CLI's `MavenProjectScaffolder` template (6 stale
  `<groupId>` entries); the docs' Maven-example `<groupId>` snippets in
  `getting-started.md` and `testing-guide.md`; the README Maven Central
  badge URL.
- **Regex boundary sanity check**: `git grep -nE 'julclang[A-Za-z0-9-]'`
  (both repos) returns zero hits — no unbounded-match damage from the
  word-boundary-based passes.
- **Publish**: `publishToMavenLocal` produced every `julc-*` artifact under
  `~/.m2/repository/org/julclang/`, including the plugin marker at
  `org/julclang/julc/org.julclang.julc.gradle.plugin/`, confirming the
  chosen plugin id `org.julclang.julc` took effect exactly as decided.
- **`julc-examples` regression gate (the decisive check)**: rebuilt clean
  against the republished `0.1.0-pre17-8fb33f4-SNAPSHOT`. The generated
  `plutus.json`'s `hash` and `compiledCode` are **byte-identical for all 58
  validator entries** to the pre-rename baseline; all 41 per-validator
  `.plutus.json` files are byte-identical; the `definitions` (CIP-57 schema)
  section is identical (61/61 keys, zero content diffs) — no FQCN leaked
  into the public blueprint schema. `./gradlew test`: 420 tests / 11 skipped
  / 0 failures — matches the historical baseline pattern exactly.
- **Non-semantic diff found and diagnosed**: the full-file `plutus.json`
  diff showed the embedded build `version` string changing (expected — it
  encodes the git SHA) and 3 of 58 `validators[]` array entries appearing in
  a different array position (content, hash, and schema refs identical).
  Traced to `RoundEnvironment` annotation-element enumeration order in the
  annotation processor scanning the *consumer's own* `com.example.validators.*`
  classes — a pre-existing, order-of-discovery-dependent nondeterminism
  unrelated to this rename (the reordered classes are julc-examples' own
  code, never touched by this change, and their schema `$ref`s use a short
  `@julc:`-alias convention that never contained the old package name).
  Filed as a pre-existing gap against AGENTS.md's determinism principle, not
  a rename regression.

## Unresolved / Follow-up (not addressed in this pass)

- **`CompileJulcTask` pre-existing bug** (unrelated to this rename,
  reproduced independently on pre-rename `fe4dbb9d`): the Gradle plugin's
  `.plutus`-DSL source-set validation requires `src/main/plutus` to exist
  even when unused, failing the build otherwise. Worked around locally by
  creating the (untracked, empty) directory in `julc-examples`; recommend
  filing an issue to make that `@InputDirectory` optional.
- **`julc-playground` test flakiness**: two different tests failed on two
  separate full-`./gradlew build` runs (`EvaluateControllerTest`, then
  `ScenariosControllerTest`), each passing when the module's tests ran
  alone. Consistent with resource contention between concurrent
  Javalin/Jetty test servers under full-repo parallel test load. Not
  confirmed against the pre-rename baseline under identical load, so treat
  as a known pre-existing risk to re-check, not a ruled-out regression.
- **Not exercised by this verification pass**: GraalVM native-image builds
  for `julc-cli`/`julc-playground` (215 reachability-metadata edits in
  `julc-cli`), Yaci DevKit end-to-end, and the Scalus/Truffle VM backends
  beyond their unit tests.
- **`julc-examples`**: `build.gradle` + 161 Java files + docs updated and
  verified, left **uncommitted** on the user's existing
  `fix/julc_pre13_changes` branch (only pre-existing uncommitted change
  there was `build.gradle`'s own version pin — nothing else was clobbered).
  Recommend committing on a fresh branch once reviewed.
- **Blueprint validator-array ordering** (see Verification Evidence above):
  pre-existing, not caused by this rename, but worth its own issue given
  AGENTS.md's determinism principle.
- **Documentation**: no migration/release-note entry was added for this
  breaking change (old→new groupId, package roots, plugin id). Recommended
  before merge, since every existing external consumer's build file breaks
  silently otherwise.

## Open Questions

- Sibling repos (`julc-helloworld`, `julc-cip113`, `julc-cip113-example`)
  need their own follow-up pass; not scheduled here. Also stale:
  `adr/evidence/036-local-examples.init.gradle` (keys on the old groupId)
  and the local Claude Code test skills
  (`julc-test-quick`/`julc-test-julc-examples`/`julc-smoke-*`) that
  hardcode `com.bloxbean.cardano`.

## Addendum (2026-09-11): docs site in scope

The docs site (`docs/`) — content pages and generator scripts
(`generate-catalog.mjs`, `generate-llms-txt.mjs`) — is in scope for both the
mechanical text replace **and** verification: after the rename, the docs
build must be run and spot-checked (generated catalog/llms.txt output,
in-page code samples) to confirm no stale `com.bloxbean.cardano.julc` /
`com.bloxbean.julc` / old-groupId references remain in rendered output. This
folds into M3 (in-repo verification) rather than a separate milestone.
