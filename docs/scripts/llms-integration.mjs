// Astro integration that exposes the JuLC AI ingestion artifacts:
//   /llms.txt              curated index (llmstxt.org convention)
//   /llms-full.txt         full docsite concatenated for ingestion
//   /ai/starter-pack.md    raw markdown copy of the AI Starter Pack
//   /ai/index.md           raw markdown copy of the /ai/ landing page
//   /ai/catalog.json       machine-readable stdlib + ledger types catalog
//
// - `astro build` (production): writes the files into the build output dir
//   (`dist/`) so they ship alongside the static site.
// - `astro dev` (local): registers a Vite middleware that generates the
//   content on demand, so requests like
//   http://localhost:4321/llms.txt and
//   http://localhost:4321/ai/catalog.json
//   work without touching `public/` or restarting on doc edits.

import { fileURLToPath } from 'node:url';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs/promises';
import { generateLlmsFiles } from './generate-llms-txt.mjs';
import { writeCatalog, generateCatalog } from './generate-catalog.mjs';

// Paths the dev middleware intercepts. Order matches what we publish at build time.
const SERVED_PATHS = new Set([
  '/llms.txt',
  '/llms-full.txt',
  '/ai/starter-pack.md',
  '/ai/index.md',
  '/ai/catalog.json',
  '/ai/diagnostics.json',
  '/ai/examples.json',
]);

export default function llmsIntegration() {
  return {
    name: 'julc-llms-txt',
    hooks: {
      'astro:build:done': async ({ dir, logger }) => {
        const outDir = fileURLToPath(dir);
        try {
          // Generate catalog first; pass it into the llms generator so the
          // starter pack injection uses the same data and we avoid double work.
          const catalog = await generateCatalog({ logger });
          await generateLlmsFiles({ outDir, logger, catalog });
          await writeCatalog({ outDir, logger });
        } catch (err) {
          logger.error(`[llms-txt] generation failed: ${err.stack || err.message}`);
          throw err;
        }
      },

      'astro:server:setup': async ({ server, logger }) => {
        // Generate once into a temp dir at server start, then re-generate
        // on every matching request (cheap — just reads ~16 .md files).
        const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'julc-llms-'));

        const silentLogger = { info: () => {}, error: (m) => logger.error(m) };
        async function ensureFresh() {
          const catalog = await generateCatalog({ logger: silentLogger });
          await generateLlmsFiles({ outDir: tmpDir, logger: silentLogger, catalog });
          await writeCatalog({ outDir: tmpDir, logger: silentLogger });
        }

        // Warm cache once so the very first request is fast.
        try {
          await ensureFresh();
          logger.info(`[llms-txt] dev middleware ready (${[...SERVED_PATHS].join(', ')})`);
        } catch (err) {
          logger.error(`[llms-txt] dev warmup failed: ${err.message}`);
        }

        server.middlewares.use(async (req, res, next) => {
          // Strip query string for matching.
          const reqUrl = (req.url || '').split('?')[0];
          if (!SERVED_PATHS.has(reqUrl)) {
            return next();
          }

          try {
            await ensureFresh();
            const fileOnDisk = path.join(tmpDir, reqUrl.replace(/^\//, ''));
            const body = await fs.readFile(fileOnDisk, 'utf8');
            const contentType = reqUrl.endsWith('.json')
              ? 'application/json; charset=utf-8'
              : 'text/markdown; charset=utf-8';
            res.statusCode = 200;
            res.setHeader('Content-Type', contentType);
            res.setHeader('Cache-Control', 'no-store');
            res.end(body);
          } catch (err) {
            logger.error(`[llms-txt] dev serve failed for ${reqUrl}: ${err.message}`);
            res.statusCode = 500;
            res.end(`llms-txt generation error: ${err.message}`);
          }
        });
      },
    },
  };
}
