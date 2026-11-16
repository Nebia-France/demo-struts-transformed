// Static server used only by the Lighthouse CI gate (`npm run perf:lh`, W7-T01).
//
// The production build cannot paint without the gateway: `provideAppInitializer`
// (src/app/app.config.ts) awaits `GET /api/v1/csrf-token` before the first render, and
// `catalogueResolver` awaits `GET /api/v1/products`. A plain static server answers those with 404,
// the initializer rejects and Lighthouse reports NO_FCP. This server therefore serves
// dist/souscription-web/browser with the SPA fallback and answers the two read operations the
// first paint depends on, with contract-shaped bodies (amounts and rate as decimal strings).
// It is a measurement harness only: it is never deployed (nginx serves the real build).
import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';

const ROOT = resolve('dist/souscription-web/browser');
const PORT = Number(process.env.PERF_PORT ?? 4300);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
};

// Contract `issueCsrfToken` body: `{ token }`, at least 32 characters.
const PERF_CSRF_TOKEN = 'perf-measurement-token-0123456789abcdef';

const PRODUCTS = [
  { code: 'PRUDENT', name: 'Horizon Prudent', profile: 'Prudent', description: 'Support securise, volatilite faible.', annualRate: '0.020000' },
  { code: 'EQUILIBRE', name: 'Horizon Equilibre', profile: 'Equilibre', description: 'Repartition equilibree entre obligations et actions.', annualRate: '0.040000' },
  { code: 'DYNAMIQUE', name: 'Horizon Dynamique', profile: 'Dynamique', description: 'Exposition actions majoritaire, volatilite elevee.', annualRate: '0.070000' },
];

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
  });
  res.end(payload);
}

function sendFile(res, file, acceptEncoding = '') {
  const type = MIME[extname(file)] ?? 'application/octet-stream';
  // Same negotiation as `gzip_static` in deploy/nginx.conf: the precompressed variant written by
  // scripts/precompress.mjs is served verbatim. Without this the measurement would model
  // uncompressed transfer sizes and overstate the LCP.
  for (const [token, suffix] of [['gzip', '.gz']]) {
    if (acceptEncoding.includes(token) && existsSync(file + suffix)) {
      res.writeHead(200, { 'Content-Type': type, 'Content-Encoding': token, Vary: 'Accept-Encoding' });
      createReadStream(file + suffix).pipe(res);
      return;
    }
  }
  res.writeHead(200, { 'Content-Type': type, Vary: 'Accept-Encoding' });
  createReadStream(file).pipe(res);
}

/** Resolves a request path inside ROOT only; any traversal attempt falls back to index.html. */
function resolveStatic(pathname) {
  const candidate = resolve(join(ROOT, normalize(pathname)));
  if (!candidate.startsWith(ROOT + sep)) {
    return null;
  }
  return existsSync(candidate) && statSync(candidate).isFile() ? candidate : null;
}

createServer((req, res) => {
  const { pathname } = new URL(req.url, `http://localhost:${PORT}`);

  if (pathname === '/api/v1/csrf-token') {
    res.setHeader('Set-Cookie', `XSRF-TOKEN=${PERF_CSRF_TOKEN}; Path=/; SameSite=Strict`);
    sendJson(res, 200, { token: PERF_CSRF_TOKEN });
    return;
  }
  if (pathname === '/api/v1/products') {
    sendJson(
      res,
      200,
      PRODUCTS.map(({ code, name, profile, annualRate }) => ({ code, name, profile, annualRate })),
    );
    return;
  }
  if (pathname.startsWith('/api/v1/products/')) {
    const code = decodeURIComponent(pathname.slice('/api/v1/products/'.length));
    sendJson(res, 200, PRODUCTS.find((p) => p.code === code) ?? PRODUCTS[0]);
    return;
  }
  if (pathname.startsWith('/api/')) {
    sendJson(res, 404, { type: 'about:blank', title: 'Not Found', status: 404 });
    return;
  }

  const file = resolveStatic(pathname);
  sendFile(res, file ?? join(ROOT, 'index.html'), String(req.headers['accept-encoding'] ?? ''));
}).listen(PORT, () => {
  console.log(`perf-server listening on http://localhost:${PORT} (root ${ROOT})`);
});
