// Static host of the behavioural gate (`npm run e2e`, W8-T04).
//
// Serves the production build (dist/souscription-web/browser) the way deploy/nginx.conf does:
// `try_files $uri $uri/ /index.html`, `/healthz`, every server-level `add_header` directive of
// that file, including the `$souscription_cache_control` map and the per-response `$request_id`
// CSP nonce, and the `sub_filter` substitutions applied to the HTML entry document. The directives are read from
// deploy/nginx.conf at start-up, so the security and cache headers asserted by the Gherkin steps
// have a single source of truth; the container itself stays verified by deploy/smoke-headers.sh.
//
// It answers no `/api/v1` call: in the browser those are fulfilled by the gateway test double of
// e2e/support/fake-gateway.ts. It is a test harness only and is never deployed.
import { randomBytes } from 'node:crypto';
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';

const ROOT = resolve('dist/souscription-web/browser');
const PORT = Number(process.env.E2E_PORT ?? 4400);
const NGINX_CONF = resolve('deploy/nginx.conf');

// nginx `mime.types` defaults; no `charset` directive is set in deploy/nginx.conf.
const MIME = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

/** Parses the `map` blocks and the server-level `add_header` directives of nginx.conf. */
function readNginxHeaders(source) {
  const text = source.replace(/#[^\n]*/g, '');
  const maps = {};
  for (const [, variable, body] of text.matchAll(/map\s+\$\w+\s+\$(\w+)\s*\{([^}]*)\}/g)) {
    const entries = [];
    for (const [, key, value] of body.matchAll(/("[^"]*"|\S+)\s+"([^"]*)"\s*;/g)) {
      entries.push({ key: key.replace(/^"|"$/g, ''), value });
    }
    maps[variable] = entries;
  }
  const server = text.slice(text.indexOf('server {'));
  const serverLevel = server.replace(/location[^{]*\{[^}]*\}/g, '');
  const headers = [];
  for (const [, name, raw] of serverLevel.matchAll(/add_header\s+([\w-]+)\s+("[^"]*"|\S+)\s+always\s*;/g)) {
    headers.push({ name, value: raw.replace(/^"|"$/g, '') });
  }
  const subFilters = [];
  for (const [, from, to] of server.matchAll(/sub_filter\s+'([^']*)'\s+'([^']*)'\s*;/g)) {
    subFilters.push({ from, to });
  }
  return { maps, headers, subFilters };
}

function resolveMap(entries, uri) {
  let fallback = '';
  for (const { key, value } of entries) {
    if (key === 'default') {
      fallback = value;
    } else if (key.startsWith('~*')) {
      if (new RegExp(key.slice(2), 'i').test(uri)) return value;
    } else if (key.startsWith('~')) {
      if (new RegExp(key.slice(1)).test(uri)) return value;
    } else if (key === uri) {
      return value;
    }
  }
  return fallback;
}

const { maps, headers, subFilters } = readNginxHeaders(readFileSync(NGINX_CONF, 'utf8'));
if (!headers.length) {
  throw new Error(`e2e-server: no add_header directive found in ${NGINX_CONF}`);
}

/** nginx `$request_id`: 16 random bytes in hexadecimal, fresh for every request. */
const requestId = () => randomBytes(16).toString('hex');

function headersFor(uri, id) {
  const out = {};
  for (const { name, value } of headers) {
    out[name] = /^\$\w+$/.test(value)
      ? resolveMap(maps[value.slice(1)] ?? [], uri)
      : value.replaceAll('$request_id', id);
  }
  return out;
}

/** Resolves a request path inside ROOT only; any traversal attempt falls back to index.html. */
function resolveStatic(pathname) {
  let decoded;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    return null;
  }
  const candidate = resolve(join(ROOT, normalize(decoded)));
  if (!candidate.startsWith(ROOT + sep)) {
    return null;
  }
  return existsSync(candidate) && statSync(candidate).isFile() ? candidate : null;
}

createServer((req, res) => {
  const { pathname } = new URL(req.url, `http://localhost:${PORT}`);
  const id = requestId();

  if (pathname === '/healthz') {
    res.writeHead(200, { ...headersFor(pathname, id), 'Content-Type': 'text/plain' });
    res.end('ok');
    return;
  }

  // nginx static content answers GET and HEAD only.
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { ...headersFor(pathname, id), 'Content-Type': 'text/html' });
    res.end('<html><body><h1>405 Not Allowed</h1></body></html>');
    return;
  }

  const file = resolveStatic(pathname) ?? join(ROOT, 'index.html');
  const uri = file === join(ROOT, 'index.html') ? '/index.html' : pathname;
  const type = MIME[extname(file)] ?? 'application/octet-stream';
  res.writeHead(200, { ...headersFor(uri, id), 'Content-Type': type });
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  if (type === 'text/html') {
    let html = readFileSync(file, 'utf8');
    for (const { from, to } of subFilters) {
      html = html.replaceAll(from, to.replaceAll('$request_id', id));
    }
    res.end(html);
    return;
  }
  createReadStream(file).pipe(res);
}).listen(PORT, () => {
  console.log(`e2e-server listening on http://localhost:${PORT} (root ${ROOT})`);
});
