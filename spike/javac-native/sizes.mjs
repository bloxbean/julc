// Prints raw, gzip -9 and brotli q11 sizes (bytes) of each file argument, using Node's zlib.
import {readFileSync} from 'node:fs';
import {gzipSync, brotliCompressSync, constants} from 'node:zlib';
for (const f of process.argv.slice(2)) {
  const b = readFileSync(f);
  const gz = gzipSync(b, {level: 9}).length;
  const br = brotliCompressSync(b, {params: {[constants.BROTLI_PARAM_QUALITY]: 11, [constants.BROTLI_PARAM_SIZE_HINT]: b.length}}).length;
  console.log(`${f}\traw=${b.length}\tgzip9=${gz}\tbrotli11=${br}`);
}
