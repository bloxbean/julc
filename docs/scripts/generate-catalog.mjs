// Generate catalog.json — a machine-readable index of the JuLC stdlib API
// surface and ledger types, by parsing the public Java sources of:
//
//   julc-stdlib/src/main/java/.../stdlib/lib/*.java
//   julc-ledger-api/src/main/java/.../ledger/*.java
//
// The output is consumed by:
//   - the AI Starter Pack (auto-generated stdlib + ledger sections in B1)
//   - the future MCP server's julc_stdlib_method / julc_ledger_type tools
//   - third parties wanting structured JuLC API data
//
// We use a deliberately simple line-oriented parser — the Java sources at
// these paths are well-formed and follow consistent conventions. If a future
// change breaks parsing, the build will warn but not fail.

import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DOCS_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(DOCS_ROOT, '..');
const execFileAsync = promisify(execFile);

const STDLIB_LIB_DIR = path.join(REPO_ROOT, 'julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/lib');
const STDLIB_PKG = 'com.bloxbean.cardano.julc.stdlib.lib';
const LEDGER_DIR = path.join(REPO_ROOT, 'julc-ledger-api/src/main/java/com/bloxbean/cardano/julc/ledger');
const LEDGER_PKG = 'com.bloxbean.cardano.julc.ledger';
const DIAGNOSTICS_FILE = path.join(REPO_ROOT, 'julc-compiler/src/main/resources/diagnostics.json');
const GRADLE_PROPERTIES_FILE = path.join(REPO_ROOT, 'gradle.properties');
const VERSION_PROPERTIES_CANDIDATES = [
  path.join(REPO_ROOT, 'julc-cli/build/generated/version-props/julc-version.properties'),
  path.join(REPO_ROOT, 'julc-cli/build/resources/main/julc-version.properties'),
  path.join(REPO_ROOT, 'julc-blueprint/build/generated/version-props/julc-version.properties'),
  path.join(REPO_ROOT, 'julc-blueprint/build/resources/main/julc-version.properties'),
];
// julc-examples is a sibling repo. We probe a few likely locations so the
// catalog generator works whether the docs build is run from a developer's
// laptop or from CI (where the path may differ).
const EXAMPLES_INDEX_CANDIDATES = [
  ...(process.env.JULC_EXAMPLES_DIR
    ? [path.resolve(process.env.JULC_EXAMPLES_DIR, 'ai/examples-index.json')]
    : []),
  path.resolve(REPO_ROOT, '../julc-examples/ai/examples-index.json'),
  path.resolve(REPO_ROOT, '../../julc-examples/ai/examples-index.json'),
];

// Files in julc-ledger-api that are infrastructure, not user-facing types.
const LEDGER_SKIP = new Set([
  'PlutusDataConvertible.java',
  'PlutusDataCodec.java',
  'PlutusDataHelper.java',
  'ScriptContextBuilder.java',
]);

function parseProperties(raw) {
  const out = {};
  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const idx = trimmed.indexOf('=');
    if (idx === -1) continue;
    out[trimmed.slice(0, idx).trim()] = trimmed.slice(idx + 1).trim();
  }
  return out;
}

async function gitShortHash() {
  if (process.env.GITHUB_SHA && process.env.GITHUB_SHA.length >= 7) {
    return process.env.GITHUB_SHA.slice(0, 7);
  }
  try {
    const { stdout } = await execFileAsync('git', ['rev-parse', '--short', 'HEAD'], {
      cwd: REPO_ROOT,
    });
    return stdout.trim();
  } catch {
    return null;
  }
}

async function snapshotVersionWithCommit(baseVersion) {
  if (!baseVersion.endsWith('-SNAPSHOT')) return baseVersion;
  const shortHash = await gitShortHash();
  if (!shortHash) return baseVersion;
  return baseVersion.replace('-SNAPSHOT', `-${shortHash}-SNAPSHOT`);
}

export async function resolveJulcVersion() {
  if (process.env.JULC_VERSION?.trim()) {
    return process.env.JULC_VERSION.trim();
  }

  for (const candidate of VERSION_PROPERTIES_CANDIDATES) {
    try {
      const props = parseProperties(await fs.readFile(candidate, 'utf8'));
      if (props.version) return props.version;
    } catch {
      // Try the next generated resource path.
    }
  }

  try {
    const props = parseProperties(await fs.readFile(GRADLE_PROPERTIES_FILE, 'utf8'));
    if (props.version) return snapshotVersionWithCommit(props.version);
  } catch {
    // Fall through to the same default used by JulcVersionProvider's backing class.
  }

  return 'dev';
}

// ---------- Javadoc extraction ----------

// Strips a /** ... */ block to a single readable line. Keeps the first
// paragraph (up to the first blank-line equivalent or the first @tag).
function cleanJavadoc(raw) {
  if (!raw) return null;
  const lines = raw
    .replace(/^\s*\/\*\*/, '')
    .replace(/\*\/\s*$/, '')
    .split('\n')
    .map((l) => l.replace(/^\s*\*\s?/, '').trimEnd());
  const out = [];
  for (const line of lines) {
    if (/^\s*@\w+/.test(line)) break; // @param, @return, etc.
    if (/^\s*<p>\s*$/i.test(line)) {
      // Treat <p> as paragraph break — stop after the first paragraph.
      if (out.length > 0) break;
      continue;
    }
    out.push(line);
  }
  return out
    .join(' ')
    .replace(/\{@link\s+([^}]+)\}/g, '$1')
    .replace(/\{@code\s+([^}]+)\}/g, '`$1`')
    .replace(/<[^>]+>/g, '')
    .replace(/\s+/g, ' ')
    .trim() || null;
}

// Walk source line-by-line, collecting comments that immediately precede
// a target node. Returns { source, doc(prevLineIdx) }.
class JavaSourceScanner {
  constructor(source) {
    this.source = source;
    this.lines = source.split('\n');
  }

  // For a 0-based line index `idx`, return the Javadoc block immediately
  // above it (skipping blank lines and annotations), or null.
  docBefore(idx) {
    let i = idx - 1;
    // Skip blank lines, single-line comments, and annotation lines.
    while (i >= 0) {
      const line = this.lines[i].trim();
      if (line === '' || line.startsWith('//') || line.startsWith('@')) {
        i--;
        continue;
      }
      if (line.endsWith('*/')) break;
      return null;
    }
    if (i < 0) return null;
    // Walk backward to find /**.
    let start = i;
    while (start >= 0 && !this.lines[start].trim().startsWith('/**')) start--;
    if (start < 0) return null;
    const block = this.lines.slice(start, i + 1).join('\n');
    return cleanJavadoc(block);
  }
}

// ---------- Stdlib parsing ----------

// Parse a stdlib lib file (e.g. ListsLib.java). Extracts:
//   - className
//   - class-level Javadoc summary
//   - public static methods (name, signature, params, returns, doc)
async function parseStdlibLib(filePath) {
  const source = await fs.readFile(filePath, 'utf8');
  const scanner = new JavaSourceScanner(source);

  // Find the class declaration line.
  const classRe = /^\s*(?:public\s+|final\s+|abstract\s+)*class\s+(\w+)/;
  let classIdx = -1;
  let className = null;
  for (let i = 0; i < scanner.lines.length; i++) {
    const m = scanner.lines[i].match(classRe);
    if (m) {
      classIdx = i;
      className = m[1];
      break;
    }
  }
  if (className === null) {
    return null;
  }

  const summary = scanner.docBefore(classIdx);

  // Find public static methods. Detection regex matches the FIRST line of
  // the declaration; we then join continuation lines (paren-balanced) before
  // the full pattern match, so methods with multi-line signatures or nested
  // generics are handled correctly.
  const methods = [];
  const methodRe = /^\s*public\s+static\s+(?!class\b)([^;{=]*?)\s+(\w+)\s*\(/;
  for (let i = classIdx + 1; i < scanner.lines.length; i++) {
    const line = scanner.lines[i];
    if (!methodRe.test(line)) continue;
    // Join continuation lines until parens balance.
    let decl = line;
    let depth = countParens(line);
    let j = i;
    while (depth > 0 && j < scanner.lines.length - 1) {
      j++;
      const next = scanner.lines[j];
      decl += ' ' + next.trim();
      depth += countParens(next);
    }
    const sigMatch = decl.match(
      /^\s*public\s+static\s+([^()]*?)\s+(\w+)\s*\(([\s\S]*?)\)\s*(?:throws[^{;]*)?\s*[;{]/
    );
    if (!sigMatch) continue;
    const returns = sigMatch[1].trim();
    const name = sigMatch[2];
    const paramsRaw = sigMatch[3].trim();
    const params = paramsRaw ? parseFieldList(paramsRaw) : [];

    if (isInternalHelperMethod({ name, returns, params })) continue;

    const paramsForSig = params.map((p) => `${p.type} ${p.name}`).join(', ');
    const signature = `${returns} ${name}(${paramsForSig})`;

    methods.push({
      name,
      signature,
      params,
      returns,
      doc: scanner.docBefore(i),
    });
  }

  return {
    className,
    fqcn: `${STDLIB_PKG}.${className}`,
    summary,
    methods,
  };
}

// ---------- Ledger parsing ----------

// Parse a ledger type file. We support three kinds:
//   - record: `public record TxOut(...)`
//   - sealed: `public sealed interface Credential extends ... { record A(..) implements ...; ... }`
//   - newtype: `public record PubKeyHash(byte[] hash)` (treated as plain record)
async function parseLedgerType(filePath) {
  const source = await fs.readFile(filePath, 'utf8');
  const scanner = new JavaSourceScanner(source);
  const fileName = path.basename(filePath, '.java');

  // Try sealed interface first.
  const sealedIdx = scanner.lines.findIndex((l) =>
    /^\s*public\s+sealed\s+interface\s+\w+/.test(l)
  );
  if (sealedIdx !== -1) {
    return parseSealed(scanner, sealedIdx, fileName);
  }

  // Plain record.
  const recordIdx = scanner.lines.findIndex((l) =>
    /^\s*public\s+record\s+\w+/.test(l)
  );
  if (recordIdx !== -1) {
    return parseTopLevelRecord(scanner, recordIdx, fileName);
  }

  // Plain interface (without sealed). Used for marker interfaces like
  // PlutusDataConvertible — we skip these via the LEDGER_SKIP list.
  return null;
}

// Bracket-aware comma split — keeps generic argument lists like
// `JulcMap<PolicyId, JulcMap<TokenName, BigInteger>>` intact instead of
// splitting them into three pseudo-fields. Tracks <>, (), [], {} nesting.
function splitTopLevelCommas(raw) {
  const out = [];
  let depth = 0;
  let buf = '';
  for (const ch of raw) {
    if (ch === '<' || ch === '(' || ch === '[' || ch === '{') depth++;
    else if (ch === '>' || ch === ')' || ch === ']' || ch === '}') depth--;
    if (ch === ',' && depth === 0) {
      out.push(buf);
      buf = '';
    } else {
      buf += ch;
    }
  }
  if (buf.trim()) out.push(buf);
  return out;
}

function parseFieldList(raw) {
  const trimmed = raw.trim();
  if (!trimmed) return [];
  return splitTopLevelCommas(trimmed).map((part) => {
    const t = part.trim();
    const lastSpace = t.lastIndexOf(' ');
    if (lastSpace === -1) return { name: '', type: t };
    return {
      name: t.slice(lastSpace + 1).trim(),
      type: t.slice(0, lastSpace).trim(),
    };
  });
}

function parseTopLevelRecord(scanner, idx, _fileName) {
  // Join continuation lines until both: (a) parens balance, AND (b) we have
  // seen the class body opener `{` or terminator `;`. Records like
  // `record TxOut(...) implements PlutusDataConvertible {` close their
  // header parens on one line but the `implements ... {` lives on the next,
  // and our match needs the terminator to anchor the field-list capture.
  let decl = scanner.lines[idx];
  let depth = countParens(decl);
  let j = idx;
  while (j < scanner.lines.length - 1 && (depth > 0 || !/[;{]/.test(decl))) {
    j++;
    const next = scanner.lines[j];
    decl += ' ' + next.trim();
    depth += countParens(next);
  }
  const m = decl.match(/^\s*public\s+record\s+(\w+)\s*\(([\s\S]*?)\)\s*(?:implements|extends|;|\{)/);
  if (!m) return null;
  const name = m[1];
  const fields = parseFieldList(m[2]);
  const methods = collectPublicMethods(scanner, j + 1);
  return {
    name,
    kind: 'record',
    fqcn: `${LEDGER_PKG}.${name}`,
    doc: scanner.docBefore(idx),
    fields,
    methods,
  };
}

// Methods we never want to surface in the catalog — they are PlutusData
// (de)serialization infrastructure, not part of the on-chain API.
const SKIP_METHOD_NAMES = new Set([
  'toPlutusData',
  'fromPlutusData',
  'equals',
  'hashCode',
  'toString',
]);

// Hide internal helpers from the AI-facing catalog. We filter on:
//
//   1. Leading underscore in the name (project convention for the "raw" twin
//      of a typed method, e.g. `_assetOf` next to `assetOf`).
//   2. A parameter or return type naming `PlutusData.<subtype>` (e.g.
//      `PlutusData.MapData`, `PlutusData.ConstrData`) — these are internal
//      pair-list / inner-map plumbing helpers (`negateTokenMap`,
//      `adjustInnerForAdd`, `findTokenAmount`, etc.). The project rule is
//      "do not hand users PlutusData subtypes"; surfacing such methods in
//      the AI catalog would directly contradict it (Codex review P1.4).
//   3. Methods named in {`toPlutusData`, `fromPlutusData`, `equals`,
//      `hashCode`, `toString`} — handled separately by SKIP_METHOD_NAMES.
//
// Note: bare `PlutusData` (the union type) is intentionally allowed —
// `MapLib.lookup`, `MapLib.member` etc. legitimately return/accept it.
//
// SKIP_STDLIB_HELPERS — stdlib internal helpers that are public-static
// for compilation reasons but are NOT user-facing API. Codex review
// finding 2: surfacing these teaches agents to call low-level helpers
// instead of canonical methods (geqMultiAsset, flatten, ...). Mirror
// this list in StdlibCatalog.java if you add to it.
const SKIP_STDLIB_HELPERS = new Set([
  'checkPolicyGeq', 'flattenStep', 'flattenPolicy',
  'adjustOuterForAdd', 'adjustInnerForAdd',
  'extraOuterEntries', 'extraInnerEntries',
]);

function isInternalHelperMethod({ name, returns, params }) {
  if (name.startsWith('_')) return true;
  if (SKIP_STDLIB_HELPERS.has(name)) return true;
  const exposesPlutusDataSubtype = (t) => /\bPlutusData\.[A-Z]\w+/.test(t);
  if (exposesPlutusDataSubtype(returns)) return true;
  for (const p of params) {
    if (exposesPlutusDataSubtype(p.type)) return true;
  }
  return false;
}

// Collect explicitly-declared public instance/static methods within a record
// or class body, starting from `startIdx`. Stops at the first unmatched closing
// brace at depth 0 (end of class body) — sufficient for the well-formed sources
// we parse. Returns [] on any parse difficulty.
function collectPublicMethods(scanner, startIdx) {
  const methods = [];
  const methodRe = /^\s*public\s+(?:static\s+)?(?!record\b|class\b|interface\b)([^;{=]*?)\s+(\w+)\s*\(/;
  for (let i = startIdx; i < scanner.lines.length; i++) {
    const line = scanner.lines[i];
    if (!methodRe.test(line)) continue;
    let decl = line;
    let depth = countParens(line);
    let j = i;
    while (depth > 0 && j < scanner.lines.length - 1) {
      j++;
      const next = scanner.lines[j];
      decl += ' ' + next.trim();
      depth += countParens(next);
    }
    const sigMatch = decl.match(
      /^\s*public\s+(static\s+)?([^()]*?)\s+(\w+)\s*\(([\s\S]*?)\)\s*(?:throws[^{;]*)?\s*[;{]/
    );
    if (!sigMatch) continue;
    const isStatic = !!sigMatch[1];
    const returns = sigMatch[2].trim();
    const methodName = sigMatch[3];
    if (SKIP_METHOD_NAMES.has(methodName)) continue;
    const paramsRaw = sigMatch[4].trim();
    const params = paramsRaw ? parseFieldList(paramsRaw) : [];
    if (isInternalHelperMethod({ name: methodName, returns, params })) continue;
    const paramsForSig = params.map((p) => `${p.type} ${p.name}`).join(', ');
    methods.push({
      name: methodName,
      static: isStatic,
      signature: `${returns} ${methodName}(${paramsForSig})`,
      params,
      returns,
      doc: scanner.docBefore(i),
    });
  }
  return methods;
}

function parseSealed(scanner, idx, fileName) {
  const headerMatch = scanner.lines[idx].match(
    /public\s+sealed\s+interface\s+(\w+)/
  );
  const name = headerMatch ? headerMatch[1] : fileName;
  const doc = scanner.docBefore(idx);

  // Walk the body and collect nested records. Detection regex matches the
  // first line of a `record Variant(` declaration without requiring the closing
  // `)` to be on the same line (multi-line records like GovernanceAction's
  // variants would otherwise be silently dropped). After detection we join
  // continuation lines, balanced by parenthesis depth, before extracting fields.
  const variants = [];
  const detectRe = /^\s*(?:public\s+|static\s+|final\s+)*record\s+(\w+)\s*\(/;
  for (let i = idx + 1; i < scanner.lines.length; i++) {
    const line = scanner.lines[i];
    if (!detectRe.test(line)) continue;
    // Join continuation lines until parens balance AND we have seen the
    // record terminator (`{`, `;`, or `implements ...`). Multi-line variants
    // like GovernanceAction's `ParameterChange(...)` close their parens on
    // one line but `implements GovernanceAction {` lives on the next.
    let decl = line;
    let depth = countParens(line);
    let j = i;
    while (j < scanner.lines.length - 1 && (depth > 0 || !/[;{]/.test(decl) && !/implements\s+\w/.test(decl))) {
      j++;
      const next = scanner.lines[j];
      decl += ' ' + next.trim();
      depth += countParens(next);
    }
    const inner = decl.match(/record\s+(\w+)\s*\(([\s\S]*?)\)\s*(?:implements|extends|;|\{)/);
    if (!inner) continue;
    variants.push({
      name: inner[1],
      fields: parseFieldList(inner[2]),
      doc: scanner.docBefore(i),
    });
  }

  return {
    name,
    kind: 'sealed',
    fqcn: `${LEDGER_PKG}.${name}`,
    doc,
    variants,
  };
}

// Net change in '(' minus ')' for a single line. Used to balance multi-line
// declarations during continuation-joining.
function countParens(line) {
  let depth = 0;
  for (const ch of line) {
    if (ch === '(') depth++;
    else if (ch === ')') depth--;
  }
  return depth;
}

// ---------- Public API ----------

export async function generateCatalog({ logger } = {}) {
  const log = (m) => (logger?.info ? logger.info(m) : console.log(m));
  const julcVersion = await resolveJulcVersion();

  // ---- Stdlib
  const stdlibFiles = (await fs.readdir(STDLIB_LIB_DIR)).filter((f) => f.endsWith('.java'));
  const stdlib = {};
  for (const f of stdlibFiles.sort()) {
    const parsed = await parseStdlibLib(path.join(STDLIB_LIB_DIR, f));
    if (!parsed) {
      log(`[catalog] skipped stdlib ${f} (no class declaration found)`);
      continue;
    }
    stdlib[parsed.className] = {
      fqcn: parsed.fqcn,
      summary: parsed.summary,
      methodCount: parsed.methods.length,
      methods: parsed.methods,
    };
  }

  // ---- Ledger
  const ledgerFiles = (await fs.readdir(LEDGER_DIR))
    .filter((f) => f.endsWith('.java'))
    .filter((f) => !LEDGER_SKIP.has(f));
  const ledger = {};
  for (const f of ledgerFiles.sort()) {
    const parsed = await parseLedgerType(path.join(LEDGER_DIR, f));
    if (!parsed) {
      log(`[catalog] skipped ledger ${f} (not a record or sealed interface)`);
      continue;
    }
    ledger[parsed.name] = parsed;
  }

  // ---- Diagnostics (read curated source-of-truth)
  let diagnostics = null;
  try {
    const raw = await fs.readFile(DIAGNOSTICS_FILE, 'utf8');
    diagnostics = JSON.parse(raw);
    diagnostics.julcVersion = julcVersion;
  } catch (err) {
    log(`[catalog] could not load diagnostics.json: ${err.message}`);
  }

  // ---- Examples index (sibling julc-examples repo)
  // The catalog advertises /ai/examples.json as a stable AI-facing endpoint.
  // If the sibling repo is missing, we still tolerate the local-dev case
  // (warning only), but `JULC_REQUIRE_EXAMPLES=1` (set by CI/deploy) makes
  // the absence a hard build failure so the deployed site never ships a
  // 404 at the advertised endpoint. Phase B review feedback (issue #3).
  let examples = null;
  for (const candidate of EXAMPLES_INDEX_CANDIDATES) {
    try {
      const raw = await fs.readFile(candidate, 'utf8');
      examples = JSON.parse(raw);
      break;
    } catch {
      // try next location
    }
  }
  if (!examples) {
    const msg =
      'examples-index.json not found at any of: ' +
      EXAMPLES_INDEX_CANDIDATES.join(', ') +
      '. Check out https://github.com/bloxbean/julc-examples as a sibling ' +
      'directory of this repo, or set JULC_EXAMPLES_DIR to that checkout.';
    if (process.env.JULC_REQUIRE_EXAMPLES === '1') {
      throw new Error('[catalog] ' + msg);
    }
    log('[catalog] WARN: ' + msg);
  }

  return {
    schemaVersion: 1,
    julcVersion,
    generatedAt: new Date().toISOString(),
    counts: {
      stdlibClasses: Object.keys(stdlib).length,
      stdlibMethods: Object.values(stdlib).reduce((n, c) => n + c.methodCount, 0),
      ledgerTypes: Object.keys(ledger).length,
      diagnostics: diagnostics?.diagnostics?.length ?? 0,
      examples: examples?.examples?.length ?? 0,
    },
    stdlib,
    ledger,
    diagnostics,
    examples,
  };
}

export async function writeCatalog({ outDir, logger } = {}) {
  const catalog = await generateCatalog({ logger });
  const aiDir = path.join(outDir, 'ai');
  await fs.mkdir(aiDir, { recursive: true });
  const outPath = path.join(aiDir, 'catalog.json');
  await fs.writeFile(outPath, JSON.stringify(catalog, null, 2), 'utf8');

  // Also publish diagnostics on its own endpoint — small JSON, frequently
  // consumed by the future MCP julc_explain_diagnostic tool.
  let diagPath = null;
  if (catalog.diagnostics) {
    diagPath = path.join(aiDir, 'diagnostics.json');
    await fs.writeFile(diagPath, JSON.stringify(catalog.diagnostics, null, 2), 'utf8');
  }

  // Examples index — small JSON consumed by the future MCP julc_examples_search.
  let examplesPath = null;
  if (catalog.examples) {
    examplesPath = path.join(aiDir, 'examples.json');
    await fs.writeFile(examplesPath, JSON.stringify(catalog.examples, null, 2), 'utf8');
  }

  const log = (m) => (logger?.info ? logger.info(m) : console.log(m));
  log(`[catalog] wrote ${outPath}  (` +
      `${catalog.counts.stdlibClasses} stdlib classes, ` +
      `${catalog.counts.stdlibMethods} methods, ` +
      `${catalog.counts.ledgerTypes} ledger types, ` +
      `${catalog.counts.diagnostics} diagnostics, ` +
      `${catalog.counts.examples} examples)`);
  if (diagPath) log(`[catalog] wrote ${diagPath}`);
  if (examplesPath) log(`[catalog] wrote ${examplesPath}`);
  return { outPath, diagPath, examplesPath, catalog };
}

// CLI: `node docs/scripts/generate-catalog.mjs [outDir]`
const isMain = (() => {
  try {
    return import.meta.url === `file://${process.argv[1]}` ||
           import.meta.url === `file://${path.resolve(process.argv[1])}`;
  } catch {
    return false;
  }
})();

if (isMain) {
  const outDir = process.argv[2] || path.join(DOCS_ROOT, 'public');
  await writeCatalog({ outDir });
}
