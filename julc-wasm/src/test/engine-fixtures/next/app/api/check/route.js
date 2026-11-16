// Next.js: a route handler on the Node.js runtime runs both engine packages (ADR-077 D3, I8).
import {createJulcVm} from '@julclang/julc-vm-wasm';
import {createJulc} from '@julclang/julc-wasm';
import {checkBoth} from '../../../shared/check.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    return Response.json({node: process.version, ok: true, report: await checkBoth({createJulcVm, createJulc})});
  } catch (error) {
    return Response.json({node: process.version, ok: false, error: String(error?.stack ?? error)}, {status: 500});
  }
}
