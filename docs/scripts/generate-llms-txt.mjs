// Generate llms.txt and llms-full.txt for AI agent ingestion.
//
// llms.txt      — curated index following the llmstxt.org convention
// llms-full.txt — single-file concatenation of all docs for direct ingestion
//
// Both files are written into the Astro build output directory so they ship
// alongside the static site (julc.dev/llms.txt, julc.dev/llms-full.txt).

import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateCatalog, resolveJulcVersion } from './generate-catalog.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DOCS_ROOT = path.resolve(__dirname, '..');
const CONTENT_ROOT = path.join(DOCS_ROOT, 'src/content/docs');

// Curated section order — mirrors the Astro sidebar in astro.config.mjs.
// Files listed here are read from src/content/docs/. Missing files are skipped
// gracefully so the build does not fail if a doc is renamed.
const SECTIONS = [
  {
    title: 'AI',
    files: [
      'ai/index.md',
      'ai/starter-pack.md',
      'ai/transcripts/closed-loop-walkthrough.md',
    ],
  },
  {
    title: 'Overview',
    files: ['overview.mdx'],
  },
  {
    title: 'Getting Started',
    files: [
      'first-contract.md',
      'getting-started.md',
    ],
  },
  {
    title: 'Guides',
    files: [
      'guides/advanced-guide.md',
      'guides/for-loop-patterns.md',
      'guides/testing-guide.md',
      'guides/source-maps.md',
    ],
  },
  {
    title: 'Standard Library',
    files: ['stdlib/stdlib-guide.md'],
  },
  {
    title: 'Reference',
    files: [
      'reference/api-reference.md',
      'reference/release-notes.md',
      'reference/library-developer-guide.md',
      'reference/examples.mdx',
      'reference/troubleshooting.md',
    ],
  },
  {
    title: 'Internals',
    files: [
      'internals/compiler-design.md',
      'internals/java-to-uplc-end-to-end.md',
      'internals/compiler-developer-guide.md',
    ],
  },
  {
    title: 'Experimental',
    files: ['experimental/jrl-guide.md'],
  },
];

function stripFrontmatter(content) {
  if (!content.startsWith('---')) {
    return { frontmatter: {}, body: content };
  }
  const end = content.indexOf('\n---', 4);
  if (end === -1) {
    return { frontmatter: {}, body: content };
  }
  const fmRaw = content.slice(4, end).trim();
  const body = content.slice(end + 4).replace(/^\n+/, '');
  const frontmatter = {};
  for (const line of fmRaw.split('\n')) {
    const m = line.match(/^([A-Za-z_][A-Za-z0-9_]*):\s*(.*)$/);
    if (m) {
      frontmatter[m[1]] = m[2].replace(/^["']|["']$/g, '').trim();
    }
  }
  return { frontmatter, body };
}

function slugFromFile(rel) {
  const noExt = rel.replace(/\.(md|mdx)$/, '');
  return noExt === 'index' || noExt.endsWith('/index') ? noExt.replace(/\/index$/, '') : noExt;
}

function urlFromFile(rel) {
  const slug = slugFromFile(rel);
  return slug ? `https://julc.dev/${slug}/` : 'https://julc.dev/';
}

// Recursively walk the content directory and return relative paths (POSIX
// separator) of every .md / .mdx file. Used by the SECTIONS-completeness
// check below.
async function listAllDocs(root) {
  const out = [];
  async function walk(dir, prefix) {
    let entries;
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) {
        await walk(full, rel);
      } else if (entry.isFile() && /\.(md|mdx)$/.test(entry.name)) {
        out.push(rel);
      }
    }
  }
  await walk(root, '');
  out.sort();
  return out;
}

async function readDoc(rel) {
  const abs = path.join(CONTENT_ROOT, rel);
  try {
    const raw = await fs.readFile(abs, 'utf8');
    const { frontmatter, body } = stripFrontmatter(raw);
    return { ok: true, rel, abs, frontmatter, body };
  } catch (err) {
    return { ok: false, rel, error: err.message };
  }
}

// Strip MDX-specific syntax that confuses pure-markdown consumers (LLMs):
// - import statements
// - JSX components
function sanitizeBodyForLlms(body) {
  return body
    // Drop ESM imports
    .replace(/^\s*import\s.+?from\s+['"][^'"]+['"];?\s*$/gm, '')
    // Drop self-closing JSX components like <CardGrid ... />
    .replace(/<[A-Z][A-Za-z0-9]*\s[^>]*\/>/g, '')
    // Drop opening JSX tags <CardGrid> ... </CardGrid> (greedy across newlines, but per-component)
    .replace(/<([A-Z][A-Za-z0-9]*)(\s[^>]*)?>([\s\S]*?)<\/\1>/g, '$3')
    // Trim resulting empty triple-newlines
    .replace(/\n{3,}/g, '\n\n');
}

function demoteHeadings(body, by = 2) {
  // Demote H1-H4 by `by` levels so they nest cleanly under our section headers.
  return body.replace(/^(#{1,4})\s/gm, (_, hashes) => '#'.repeat(hashes.length + by) + ' ');
}

// ---- Catalog injection ---------------------------------------------------

// Replace the contents between `<!-- catalog:<name>-start -->` and
// `<!-- catalog:<name>-end -->` with `replacement`. If the anchors are not
// found, returns the body unchanged.
function injectBetweenAnchors(body, name, replacement) {
  const start = `<!-- catalog:${name}-start -->`;
  const end = `<!-- catalog:${name}-end -->`;
  const startIdx = body.indexOf(start);
  if (startIdx === -1) return body;
  const endIdx = body.indexOf(end, startIdx + start.length);
  if (endIdx === -1) return body;
  return (
    body.slice(0, startIdx + start.length) +
    '\n\n' +
    replacement.trim() +
    '\n\n' +
    body.slice(endIdx)
  );
}

function renderStdlibBlock(catalog) {
  if (!catalog?.stdlib) return '';
  const out = [];
  out.push('### Full machine-readable stdlib reference');
  out.push('');
  out.push('*Auto-generated from `julc-stdlib/.../lib/*.java`. Source of truth: [/ai/catalog.json](/ai/catalog.json).*');
  out.push('');
  for (const [className, info] of Object.entries(catalog.stdlib)) {
    out.push(`#### ${className}`);
    if (info.summary) {
      out.push('');
      out.push(`*${info.summary}*`);
    }
    out.push('');
    if (!info.methods || info.methods.length === 0) {
      out.push('_(no public static methods)_');
      out.push('');
      continue;
    }
    for (const m of info.methods) {
      const docPart = m.doc ? ` — ${m.doc}` : '';
      out.push(`- \`${m.signature}\`${docPart}`);
    }
    out.push('');
  }
  return out.join('\n');
}

function renderLedgerBlock(catalog) {
  if (!catalog?.ledger) return '';
  const out = [];
  out.push('### Full machine-readable ledger types reference');
  out.push('');
  out.push('*Auto-generated from `julc-ledger-api/.../ledger/*.java`. Source of truth: [/ai/catalog.json](/ai/catalog.json).*');
  out.push('');

  const records = [];
  const sealed = [];
  for (const [name, t] of Object.entries(catalog.ledger)) {
    if (t.kind === 'sealed') sealed.push([name, t]);
    else records.push([name, t]);
  }

  if (records.length > 0) {
    out.push('#### Records');
    out.push('');
    for (const [name, t] of records) {
      const fieldList = (t.fields || [])
        .map((f) => `\`${f.type} ${f.name}\``)
        .join(', ');
      const docPart = t.doc ? ` — ${t.doc}` : '';
      out.push(`- **\`${name}\`**${docPart}`);
      if (fieldList) out.push(`  - Fields: ${fieldList}`);
      if (t.methods && t.methods.length > 0) {
        const methodList = t.methods
          .map((m) => `\`${m.signature}\``)
          .join(', ');
        out.push(`  - Methods: ${methodList}`);
      }
    }
    out.push('');
  }

  if (sealed.length > 0) {
    out.push('#### Sealed interfaces');
    out.push('');
    for (const [name, t] of sealed) {
      const docPart = t.doc ? ` — ${t.doc}` : '';
      out.push(`- **\`${name}\`**${docPart}`);
      for (const v of t.variants || []) {
        const fieldList = (v.fields || [])
          .map((f) => `${f.type} ${f.name}`)
          .join(', ');
        out.push(`  - \`${v.name}(${fieldList})\``);
      }
    }
    out.push('');
  }

  return out.join('\n');
}

// Apply catalog injection to a starter-pack body. Used for the raw-md copy
// served at /ai/starter-pack.md and for the llms-full.txt inclusion.
export function injectCatalogIntoStarterPack(body, catalog) {
  if (!catalog) return body;
  let out = body;
  out = injectBetweenAnchors(out, 'stdlib', renderStdlibBlock(catalog));
  out = injectBetweenAnchors(out, 'ledger', renderLedgerBlock(catalog));
  return out;
}

export async function generateLlmsFiles({ outDir, logger, catalog }) {
  const log = (msg) => {
    if (logger?.info) logger.info(msg);
    else console.log(msg);
  };

  // Catalog is injected into starter-pack.md and llms-full.txt at publish time.
  // Caller may pass a pre-generated catalog (avoiding double work); otherwise
  // we generate it here.
  const catalogData = catalog ?? (await generateCatalog({ logger: { info: () => {} } }).catch((err) => {
    log(`[llms-txt] catalog unavailable, skipping starter-pack injection: ${err.message}`);
    return null;
  }));
  const julcVersion = catalogData?.julcVersion ?? await resolveJulcVersion();
  const generatedAt = new Date().toISOString();

  // Resolve all documented files, skipping missing ones.
  const resolved = [];
  const includedRels = new Set();
  for (const section of SECTIONS) {
    const items = [];
    for (const f of section.files) {
      const d = await readDoc(f);
      if (d.ok) {
        items.push(d);
        includedRels.add(d.rel);
      } else {
        log(`[llms-txt] skip ${f} (${d.error})`);
      }
    }
    if (items.length > 0) {
      resolved.push({ title: section.title, items });
    }
  }

  // Codex review finding 8: walk the content dir and pick up any docs that
  // SECTIONS does not list. They go into an "Other" section at the end so
  // they are not silently missing from llms.txt / llms-full.txt — but the
  // build also logs a warning so a maintainer notices the oversight.
  const allDocs = await listAllDocs(CONTENT_ROOT);
  const unlisted = allDocs.filter((rel) => !includedRels.has(rel));
  if (unlisted.length > 0) {
    const items = [];
    for (const rel of unlisted) {
      const d = await readDoc(rel);
      if (!d.ok) continue;
      items.push(d);
      const requireListed = process.env.JULC_REQUIRE_LISTED_DOCS === '1';
      const msg = `[llms-txt] WARNING: ${rel} is not listed in SECTIONS — appending to "Other"`;
      if (requireListed) {
        throw new Error(msg + ' (JULC_REQUIRE_LISTED_DOCS=1)');
      }
      log(msg);
    }
    if (items.length > 0) {
      resolved.push({ title: 'Other', items });
    }
  }

  // ---------- llms.txt (curated index) ----------
  const indexLines = [];
  indexLines.push('# JuLC');
  indexLines.push('');
  indexLines.push(`julcVersion: ${julcVersion}`);
  indexLines.push(`generatedAt: ${generatedAt}`);
  indexLines.push('');
  indexLines.push(
    '> JuLC compiles a safe subset of Java to Plutus V3 UPLC for Cardano smart contracts. ' +
    'Java developers write validators as ordinary classes (records, sealed interfaces, switch ' +
    'expressions); the compiler produces efficient Plutus scripts.'
  );
  indexLines.push('');
  indexLines.push('Key facts an AI agent should know before generating JuLC code:');
  indexLines.push('');
  indexLines.push('- JuLC is for **on-chain** smart contract code (validators, minting policies). ' +
    'Off-chain transaction building uses cardano-client-lib, which is a separate Java library.');
  indexLines.push('- Target: **Plutus V3 only** (Conway era).');
  indexLines.push('- Validators are annotated `@SpendingValidator`, `@MintingValidator`, `@CertifyingValidator`, ' +
    '`@WithdrawValidator`, `@VotingValidator`, or `@ProposingValidator`. The entrypoint is a `static` method ' +
    'annotated `@Entrypoint`.');
  indexLines.push('- **Always prefer high-level ledger type classes** — `TxOut`, `Value`, `Address`, ' +
    '`OutputDatum`, `Credential`, sealed-interface variants (e.g. `Vote.Yes`), `JulcList<T>`, `JulcMap<K,V>`, ' +
    '`Optional<T>`, `Tuple2`/`Tuple3` — over raw `PlutusData.ConstrData/IntData/BytesData/MapData/ListData`. ' +
    'Raw `PlutusData` constructors (subtypes: `ConstrData`, `IntData`, `BytesData`, `MapData`, `ListData`) are an **anti-pattern** in nearly all on-chain code.');
  indexLines.push('- The Java subset is restricted: no mutation after assignment, no lambda `.apply()`, ' +
    'no uninitialized variables, no `return` inside `while` loops, no reflection, no I/O.');
  indexLines.push('- Stdlib lives in `com.bloxbean.cardano.julc.stdlib.lib.*` and is imported per library: ' +
    '`ContextsLib`, `ListsLib`, `ValuesLib`, `MapLib`, `OutputLib`, `MathLib`, `IntervalLib`, `CryptoLib`, ' +
    '`ByteStringLib`, `BitwiseLib`, `AddressLib`, `BlsLib`, `NativeValueLib`.');
  indexLines.push('');
  indexLines.push('## Start here for AI agents');
  indexLines.push('');
  indexLines.push('- [AI Starter Pack](https://julc.dev/ai/starter-pack/): The single best file to ingest. ' +
    'Distills what AI agents need to write correct JuLC on first try (limitations, anti-patterns, idioms, ' +
    'error→fix table, canonical examples).');
  indexLines.push('- [llms-full.txt](https://julc.dev/llms-full.txt): All JuLC docs concatenated as a single ' +
    'markdown file (~50–150K tokens). Ingest this for full coverage.');
  indexLines.push('- [Using JuLC with AI agents](https://julc.dev/ai/): Install snippets for Claude Code, ' +
    'Cursor, Continue, ChatGPT, and other AI tools.');
  indexLines.push('');

  for (const section of resolved) {
    indexLines.push(`## ${section.title}`);
    indexLines.push('');
    for (const d of section.items) {
      const title = d.frontmatter.title || slugFromFile(d.rel);
      const desc = d.frontmatter.description ? `: ${d.frontmatter.description}` : '';
      indexLines.push(`- [${title}](${urlFromFile(d.rel)})${desc}`);
    }
    indexLines.push('');
  }

  indexLines.push('## Source code and examples');
  indexLines.push('');
  indexLines.push('- Repo: https://github.com/bloxbean/julc');
  indexLines.push('- Real-world examples: https://github.com/bloxbean/julc-examples (cftemplates, nft, ' +
    'uverify, mpf, linkedlist, swap, lending, validators) — these are the canonical reference for idiomatic JuLC.');
  indexLines.push('- Hello world: https://github.com/bloxbean/julc-helloworld');
  indexLines.push('');

  // ---------- llms-full.txt (full concat) ----------
  const fullLines = [];
  fullLines.push('# JuLC — Full Documentation (concatenated for AI ingestion)');
  fullLines.push('');
  fullLines.push('> Single-file dump of the JuLC docsite, suitable for AI agent ingestion.');
  fullLines.push('');
  fullLines.push(`julcVersion: ${julcVersion}`);
  fullLines.push(`Generated: ${generatedAt}`);
  fullLines.push('Site: https://julc.dev');
  fullLines.push('Repo: https://github.com/bloxbean/julc');
  fullLines.push('Examples: https://github.com/bloxbean/julc-examples');
  fullLines.push('');
  fullLines.push('## Table of Contents');
  fullLines.push('');
  for (const section of resolved) {
    for (const d of section.items) {
      const t = d.frontmatter.title || slugFromFile(d.rel);
      fullLines.push(`- [${section.title} → ${t}](${urlFromFile(d.rel)})`);
    }
  }
  fullLines.push('');

  for (const section of resolved) {
    fullLines.push('---');
    fullLines.push('');
    fullLines.push(`# ${section.title}`);
    fullLines.push('');
    for (const d of section.items) {
      const t = d.frontmatter.title || slugFromFile(d.rel);
      fullLines.push('---');
      fullLines.push('');
      fullLines.push(`## ${t}`);
      fullLines.push('');
      fullLines.push(`Source: ${urlFromFile(d.rel)}`);
      if (d.frontmatter.description) {
        fullLines.push('');
        fullLines.push(`> ${d.frontmatter.description}`);
      }
      fullLines.push('');
      // Inject catalog content into the starter pack before sanitization,
      // so the AI-served full-text version always has the latest stdlib /
      // ledger types reference.
      const isStarterPack = d.rel === 'ai/starter-pack.md';
      const sourceBody = isStarterPack && catalogData
        ? injectCatalogIntoStarterPack(d.body, catalogData)
        : d.body;
      const cleaned = sanitizeBodyForLlms(sourceBody);
      const demoted = demoteHeadings(cleaned, 2);
      fullLines.push(demoted.trim());
      fullLines.push('');
    }
  }

  await fs.mkdir(outDir, { recursive: true });
  const llmsTxtPath = path.join(outDir, 'llms.txt');
  const llmsFullPath = path.join(outDir, 'llms-full.txt');
  await fs.writeFile(llmsTxtPath, indexLines.join('\n'), 'utf8');
  await fs.writeFile(llmsFullPath, fullLines.join('\n'), 'utf8');

  const indexBytes = Buffer.byteLength(indexLines.join('\n'), 'utf8');
  const fullBytes = Buffer.byteLength(fullLines.join('\n'), 'utf8');
  log(`[llms-txt] wrote ${llmsTxtPath}  (${indexLines.length} lines, ${indexBytes} bytes)`);
  log(`[llms-txt] wrote ${llmsFullPath}  (${fullLines.length} lines, ${fullBytes} bytes)`);

  // Also publish raw markdown copies of the AI files so they can be fetched
  // verbatim (e.g. `curl -o CLAUDE.md https://julc.dev/ai/starter-pack.md`).
  // Starlight only emits rendered HTML otherwise.
  const rawCopies = [
    { src: 'ai/starter-pack.md', dst: 'ai/starter-pack.md' },
    { src: 'ai/index.md', dst: 'ai/index.md' },
  ];
  for (const { src, dst } of rawCopies) {
    const d = await readDoc(src);
    if (!d.ok) {
      log(`[llms-txt] skip raw copy ${src} (${d.error})`);
      continue;
    }
    const dstPath = path.join(outDir, dst);
    await fs.mkdir(path.dirname(dstPath), { recursive: true });
    // Inject catalog content into the starter-pack raw .md so the file an
    // AI agent fetches always carries the up-to-date stdlib + ledger
    // reference. Frontmatter is stripped; HTML comments preserved.
    const sourceBody = (src === 'ai/starter-pack.md' && catalogData)
      ? injectCatalogIntoStarterPack(d.body, catalogData)
      : d.body;
    const cleaned = sanitizeBodyForLlms(sourceBody);
    const stamped = src === 'ai/starter-pack.md'
      ? `<!-- julcVersion: ${julcVersion} -->\n\n${cleaned}`
      : cleaned;
    await fs.writeFile(dstPath, stamped, 'utf8');
    log(`[llms-txt] wrote ${dstPath}  (raw md)`);
  }

  return { llmsTxtPath, llmsFullPath, indexBytes, fullBytes };
}

// Allow direct execution for local testing:
//   node docs/scripts/generate-llms-txt.mjs [outDir]
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
  await generateLlmsFiles({ outDir });
}
