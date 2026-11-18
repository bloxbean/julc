// ADR-077 D8: the form of a release version, for the npm-gates job of native-image-release.yml, with the same
// classifier npm-publish.mjs uses, so the gate and the publisher never disagree. Prints `publishable` (X.Y.Z, -preN,
// -betaN, -rcN) or `prerelease-candidate` (X.Y.Z-preN-rcN: dry run only); exits 1, naming the reason, for a snapshot,
// a version outside npm's semver limits, or a form D8 has no rule for.
//   node npm-gate.mjs <version>
import {ReleaseError, versionKind} from './npm-release.mjs';

try {
  const kind = versionKind(process.argv[2]);
  console.log(kind === 'pre-rc' ? 'prerelease-candidate' : 'publishable');
} catch (error) {
  console.error(error instanceof ReleaseError ? error.message : error.stack);
  process.exitCode = 1;
}
