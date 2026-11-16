// ADR-077 D2/D4/D5: the declarations type-check from the packed @julclang/wasm-client with skipLibCheck: false, under
// moduleResolution NodeNext without the DOM library, and under bundler with it; the archives' copy (rest: true) too.
// Installs pinned TypeScript and @types/node from the registry.
//   node package-types.mjs <tarball> <archive client directory>
import {execFileSync} from 'node:child_process';
import {cpSync, mkdtempSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const [tarball, archive] = process.argv.slice(2);
const checks = fileURLToPath(new URL('../types/', import.meta.url));
const TYPESCRIPT = 'typescript@5.9.3', NODE_TYPES = '@types/node@22.20.5';
const projects = {
  nodenext: {files: ['package-check.ts', 'archive-check.ts'], packages: [TYPESCRIPT, NODE_TYPES],
    compilerOptions: {module: 'NodeNext', moduleResolution: 'NodeNext', target: 'ES2022', lib: ['ES2022'],
      types: ['node']}},
  bundler: {files: ['package-check.ts', 'archive-check.ts', 'dom-check.ts'], packages: [TYPESCRIPT],
    compilerOptions: {module: 'ESNext', moduleResolution: 'bundler', target: 'ES2022', lib: ['ES2022', 'DOM'],
      types: []}},
};
for (const [name, project] of Object.entries(projects)) {
  const dir = mkdtempSync(path.join(tmpdir(), 'julc-types-' + name + '-'));
  writeFileSync(path.join(dir, 'package.json'), JSON.stringify({name: 'types-' + name, private: true, type: 'module'}));
  execFileSync('npm', ['install', '--ignore-scripts', '--no-audit', '--no-fund', path.resolve(tarball), ...project.packages],
    {cwd: dir, stdio: 'inherit'});
  cpSync(path.resolve(archive), path.join(dir, 'archive'), {recursive: true});
  for (const file of project.files) cpSync(path.join(checks, file), path.join(dir, file));
  writeFileSync(path.join(dir, 'tsconfig.json'), JSON.stringify({files: project.files, compilerOptions: {
    ...project.compilerOptions, strict: true, skipLibCheck: false, noEmit: true, exactOptionalPropertyTypes: false}}));
  execFileSync(path.join(dir, 'node_modules', '.bin', 'tsc'), ['-p', dir], {cwd: dir, stdio: 'inherit'});
  console.log(`ok types ${name}: ${project.files.join(', ')}`);
}
