// ADR-077 D2/D4/D5: the declarations type-check from the packed @julclang/wasm-client with skipLibCheck: false, under
// moduleResolution NodeNext without the DOM library, under bundler with it (also on TypeScript 5.0), and under node10
// (typesVersions); the archives' copy (rest: true) too.
// Installs pinned TypeScript and @types/node from the registry.
// With the engine packages' tarballs (ADR-077 M3), their declarations are checked too, in each project.
//   node package-types.mjs <tarball> <archive client directory> [<engine package tarball>...]
import {execFileSync} from 'node:child_process';
import {cpSync, mkdtempSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const [tarball, archive, ...engines] = process.argv.slice(2);
const checks = fileURLToPath(new URL('../types/', import.meta.url));
const TYPESCRIPT = 'typescript@5.9.3', TYPESCRIPT_50 = 'typescript@5.0.4', NODE_TYPES = '@types/node@22.20.5';
const projects = {
  nodenext: {files: ['package-check.ts', 'archive-check.ts', 'forwarding-check.ts'], packages: [TYPESCRIPT, NODE_TYPES],
    compilerOptions: {module: 'NodeNext', moduleResolution: 'NodeNext', target: 'ES2022', lib: ['ES2022'],
      types: ['node']}},
  node10: {files: ['node10-check.ts', 'forwarding-check.ts'], packages: [TYPESCRIPT],
    compilerOptions: {module: 'ESNext', moduleResolution: 'node10', target: 'ES2022', lib: ['ES2022', 'DOM'],
      types: [], ignoreDeprecations: '5.0'}},
  bundler: {files: ['package-check.ts', 'archive-check.ts', 'dom-check.ts', 'forwarding-check.ts'], packages: [TYPESCRIPT],
    compilerOptions: {module: 'ESNext', moduleResolution: 'bundler', target: 'ES2022', lib: ['ES2022', 'DOM'],
      types: []}},
  // The oldest TypeScript the READMEs name: the engine packages re-export the client's types with export type *.
  ts50: {files: ['package-check.ts', 'archive-check.ts', 'dom-check.ts', 'forwarding-check.ts'], packages: [TYPESCRIPT_50],
    compilerOptions: {module: 'ESNext', moduleResolution: 'bundler', target: 'ES2022', lib: ['ES2022', 'DOM'],
      types: []}},
};
for (const [name, project] of Object.entries(projects)) {
  const dir = mkdtempSync(path.join(tmpdir(), 'julc-types-' + name + '-'));
  writeFileSync(path.join(dir, 'package.json'), JSON.stringify({name: 'types-' + name, private: true, type: 'module'}));
  execFileSync('npm', ['install', '--ignore-scripts', '--no-audit', '--no-fund', path.resolve(tarball),
    ...engines.map(engine => path.resolve(engine)), ...project.packages], {cwd: dir, stdio: 'inherit'});
  cpSync(path.resolve(archive), path.join(dir, 'archive'), {recursive: true});
  const files = engines.length ? [...project.files, 'engine-check.ts'] : project.files;
  for (const file of files) cpSync(path.join(checks, file), path.join(dir, file));
  writeFileSync(path.join(dir, 'tsconfig.json'), JSON.stringify({files, compilerOptions: {
    ...project.compilerOptions, strict: true, skipLibCheck: false, noEmit: true, exactOptionalPropertyTypes: false}}));
  execFileSync(path.join(dir, 'node_modules', '.bin', 'tsc'), ['-p', dir], {cwd: dir, stdio: 'inherit'});
  console.log(`ok types ${name}: ${files.join(', ')}`);
}
