# Security audit of the delivered souscription-web (W9-T03 / W9-T04)

Audit of what is delivered: the committed `package-lock.json`, the image built from `Dockerfile`,
the production bundle and the `deploy/nginx.conf` it is served with. Each section below is
required by `npm run check:audit` (wired into `npm run lint`).

Baselines to beat: 18 `npm audit` findings on Angular 21.0.0, and 7 CVEs on the Struts 1 line of
the legacy WAR (`io.github.weblegacy:struts-core:1.5.0-RC2`), which the SPA no longer ships.

| Item | Value |
| --- | --- |
| Audited source | commit `c3ebc4fc7680eb6cbe22140e78de296a14761258` (`/version.json` `release` of the audited image) |
| Audit date | 2026-11-13 |
| Runtime dependencies | `@angular/*` 21.2.26, `rxjs` 7.8.1, `tslib` 2.x |
| Image | `souscription-web:local`, base `nginxinc/nginx-unprivileged:stable-alpine` (Alpine 3.24), uid 101 |

## 1. npm audit (delivered lockfile)

Command: `npm ci && npm audit --audit-level=high` in `frontend/souscription-web`.

| Run | Critical | High | Moderate | Low | Scope |
| --- | --- | --- | --- | --- | --- |
| Before (lockfile of W9-T02) | 0 | 12 | 30 | 5 | all from `@lhci/cli` 0.14 (dev) |
| Delivered | 0 | **0** | 30 | 0 | dev dependencies only |
| Delivered, `--omit=dev` | 0 | 0 | 0 | 0 | the runtime dependencies shipped in the bundle |

```
$ npm audit --audit-level=high
...
30 moderate severity vulnerabilities
exit code 0
$ npm audit --omit=dev
found 0 vulnerabilities
```

Remediation applied:

- `@lhci/cli` 0.14 -> **0.16.1** (Lighthouse 13.6, `uuid` 14, `tmp`/`extract-zip`/`inquirer`
  chains removed).
- `overrides."basic-ftp": "^6.2.6"`: `proxy-agent` 8 -> `get-uri` 8.0.1 still asks for
  `basic-ftp@^5` (3 HIGH DoS advisories, fixed in 6.2.4+). The FTP path is never used: the
  Lighthouse CI harness only reaches the local `perf-server.mjs` over HTTP.
- The 30 remaining moderates are dev-only (`glob` GHSA-3g3m-2fjr-6pw4, `minimatch`
  GHSA-g3hv-75wg-m249, `uuid` GHSA-w5hq-g745-h8pq through `@cucumber/*` and the Angular build
  toolchain). None of them ships in the bundle. They are below the `--audit-level=high` gate.

The runtime versions were also checked against the OSV database: 0 known vulnerability for
`@angular/{core,common,forms,router,platform-browser}` 21.2.26 and `rxjs` 7.8.1.

**Result:** 0 HIGH/CRITICAL (baseline 18 findings on Angular 21.0.0). Blocking CI step:
`npm audit --audit-level=high` in both gates.

## 2. Image scan (Trivy)

Command: `trivy image --severity HIGH,CRITICAL --exit-code 1 souscription-web:local`
(Trivy 0.76.2, vulnerability DB updated 2026-11-13 12:56 UTC).

Before remediation the published `stable-alpine` tag carried 13 findings (12 HIGH, 1 CRITICAL),
all in Alpine OS packages with a fixed version already in the Alpine 3.24 repositories:

| Severity | Package | Installed -> fixed | Advisory |
| --- | --- | --- | --- |
| CRITICAL | brotli-libs | 1.2.0-r1 -> 1.2.1-r0 | CVE-2026-111497 |
| HIGH | curl, libcurl | 8.22.0-r0 -> 8.23.0-r0 | CVE-2026-92392 |
| HIGH | libexpat | 2.9.0-r0 -> 2.10.0-r0 | CVE-2026-113351, -115506, -117610, -118370 |
| HIGH | libxslt | 1.1.43-r3 -> 1.1.43-r4 | CVE-2026-125350, CVE-2026-87636, CVE-2026-9947 |
| HIGH | musl, musl-utils | 1.2.6-r2 -> 1.2.6-r3 | CVE-2026-107891 |
| HIGH | zlib | 1.3.2-r0 -> 1.3.2-r1 | CVE-2026-85091 |

Remediation: the runtime stage of `Dockerfile` runs `apk upgrade --no-cache` as root, then drops
back to the unprivileged uid 101 before copying the artifact.

```
Report Summary
┌────────────────────────────────────────┬────────┬─────────────────┬─────────┐
│                 Target                 │  Type  │ Vulnerabilities │ Secrets │
├────────────────────────────────────────┼────────┼─────────────────┼─────────┤
│ souscription-web:local (alpine 3.24.2) │ alpine │        0        │    -    │
└────────────────────────────────────────┴────────┴─────────────────┴─────────┘
exit code 0
```

Blocking CI step: `image scan (trivy)` right after `docker build` in both gates of
`.github/workflows/frontend-souscription-web.yml`. It uses the pinned `aquasec/trivy:0.76.2` image.

Local reproduction note: on the audit workstation the Docker VM can resolve neither the private
npm mirror the lockfile points to nor `dl-cdn.alpinelinux.org` without a TLS-intercepting proxy.
So the audited image was built from the committed `Dockerfile` runtime stage with two changes. The
`dist/` came from the same `npm run build` on the host (as in `docs/BENCHMARK.md` deviation 5). The
`apk upgrade` layer was produced with the workstation CA mounted read-only, and the CA was not
kept in the image. The CI build runs the `Dockerfile` unchanged.

**Result:** 0 HIGH/CRITICAL (13 on the unpatched base tag).

## 3. DAST (reflected and stored XSS)

Command: `npm run dast` (Playwright project `dast`, `e2e/dast/xss.spec.ts`), which implements the
pass defined in `deploy/dast.md` over the production bundle. Report: `e2e/reports/dast.json`, also
uploaded by the main-gate CI artifact `cucumber-report`.

- The 6 payloads of `deploy/dast.md` are injected into every input (`firstName`, `lastName`,
  `email`, `birthDate`, `initial`, `monthly`, `years`), into the path parameters
  (`/produit/{code}`, `/erreur/{code}`, both URL-encoded and raw) and into `?notice=`.
- They are also injected into every server-provided value: the catalogue list, the product
  detail, the draft re-read on `/souscription/recapitulatif`, and the confirmation reference. The
  gateway stub stores what the SPA submits and returns it on the next reads, so stored XSS is
  exercised.
- Detection runs on every crawled state. It traps `alert`/`confirm`/`prompt` calls, injected
  `script`/`img src=x`/`svg`/`iframe` nodes, `on*` attributes, `javascript:` URLs, and any
  `/api/v1` response served as HTML. The perf server sends no CSP, so the template escaping is
  tested without that defence.
- Positive control: a self-test injects `<img src=x onerror=alert(1)>` and asserts that the
  detector reports it, so a green pass cannot be vacuous.

```
19 passed (25.4s)
{ "payloads": 6, "statesCrawled": 78, "findings": 0, "details": [] }
```

Every probe is rendered as visible text on the récapitulatif (`toContainText(payload)`).

**Result:** 0 XSS finding, reflected or stored, over 78 crawled states. Blocking CI step:
`dast (xss)` in both gates.

## 4. Security headers

Command: `EXPECTED_RELEASE=c3ebc4fc7680eb6cbe22140e78de296a14761258 bash deploy/smoke-headers.sh http://localhost:18080`
against the running image.

| Header | `SecurityFilter.java` | Served |
| --- | --- | --- |
| `X-Content-Type-Options` | `nosniff` | `nosniff` |
| `X-Frame-Options` | `DENY` | `DENY` |
| `Referrer-Policy` | `no-referrer` | `no-referrer` |
| `Cache-Control` | `no-store` | `no-store` on documents, `immutable` on hashed assets |
| `Content-Security-Policy` | `script-src 'none'`, 7 other directives | the same 8 directives, `script-src 'self'`, `connect-src 'self'`, per-response `style-src` nonce, no `unsafe-*`, no wildcard |
| `Strict-Transport-Security` | — | `max-age=31536000; includeSubDomains` (added) |

```
PASS: X-Content-Type-Options: nosniff
PASS: X-Frame-Options: DENY
PASS: Referrer-Policy: no-referrer
PASS: Strict-Transport-Security: max-age=31536000
PASS: Content-Security-Policy header present
PASS: CSP free of unsafe-inline, unsafe-eval and wildcard sources
PASS: CSP style-src nonce matches the ngCspNonce of index.html
PASS: CSP nonce differs between two responses
PASS: no Access-Control header emitted (same-origin model, OD-004)
... 36 PASS, 0 FAIL, 1 SKIP (CSRF cookie, no gateway), exit code 0
```

**Result:** equivalent to or stricter than the 5 headers of `SecurityFilter.java`, plus HSTS.
The only relaxation is `script-src 'self'`, which is required for the SPA's own hashed bundles.
The `XSRF-TOKEN` cookie checks of section 7 of the script are skipped while no gateway answers
on the SPA origin (see section 5).

## 5. CSRF negative test

Commands:

- `npx cucumber-js --config e2e/cucumber.cjs --name "Soumission refusee en l'absence de jeton anti-CSRF"`
- `npm run e2e:journeys -- --grep missing-csrf`

| Check | Result |
| --- | --- |
| Gherkin scenario `Soumission refusee en l'absence de jeton anti-CSRF` | passed (6/6 steps). The whole SPA tier replayed with it: 128/128 rows |
| Journey `missing-csrf`: the SPA sends `X-CSRF-Token`, a 403 lands on `/erreur/403` | passed |
| A mutation without `X-CSRF-Token` returns **403** | asserted against the contract-shaped gateway double (`e2e/support/fake-gateway.ts`) |

**Result:** green against the gateway double. Replaying against the deployed
`souscription-gateway` is blocked until the platform team deploys it behind the SPA origin. Until
then, the 403 is asserted on the contract, not on the gateway code, and the cookie checks
(`SameSite=Strict`, `Secure`, not `HttpOnly`) of `smoke-headers.sh` are skipped.

## 6. API validation (UI bypassed)

Command: `npm run api:negative` (`e2e/api/validation.spec.ts`, one case per `InputValidator`
rule, 20 cases, HTTP 400 plus the matching `FieldErrors` key).

```
20 skipped
exit code 0
```

**Result:** exit 0, 20/20 skipped. No `souscription-gateway` answers `API_BASE_URL`. The cases talk to
the gateway directly on purpose, so they cannot be replayed against a double without losing what
they prove. This is blocked until the platform team deploys `souscription-gateway` and the backend
services. Then run `API_BASE_URL=https://<origin>/api/v1 npm run api:negative`.

## 7. Probes and version signal

| Check | Result |
| --- | --- |
| `GET /healthz` after `docker run` | 200 in 126–131 ms over 3 container starts. CI now fails if it is not ready within 5 s |
| `GET /version.json` | `{"release":"c3ebc4fc7680eb6cbe22140e78de296a14761258","builtAt":"2026-11-13T19:36:55.417Z"}`, `Cache-Control: no-store` |
| Version assertion in `smoke-headers.sh` | `release` equals `EXPECTED_RELEASE` (CI: `github.sha`), or is a commit sha. `builtAt` is ISO-8601. A mismatch fails the script |
| Error reports carry the version | `telemetry.service.spec.ts`: every payload (error, vital, journey) carries the `release` of `/version.json` |
| Diagnostic footer | `app-site-footer` renders `Version <sha:12> du <YYYY-MM-DD>`. It shows technical fields only, with no personal data. The full sha is in `data-release` |

The image build passes the sha with `--build-arg GIT_COMMIT_SHA`, because the build context has no
`.git`. A local `npm run build` falls back to `git rev-parse HEAD`.

## 8. Parity re-verification (43 parity)

Re-run on 2026-11-13 against commit `75d11ea68939ce757b22c9eb153faa84cf06e62e`, after a fresh
`npm ci`. No threshold or gate was changed.

| Parity step | Command | Result |
| --- | --- | --- |
| 1. Delivered lockfile | `npm ci && npm audit --audit-level=high` | exit 0: 0 HIGH/CRITICAL, 30 moderate (dev only). `--omit=dev`: 0 vulnerabilities |
| 2. Image scan | `trivy image --severity HIGH,CRITICAL --exit-code 1` (aquasec/trivy:0.76.2) | exit 0, `alpine 3.24.2`: 0. Blocking `image scan (trivy)` step after `docker build` in both CI gates |
| 3. Headers + version | `EXPECTED_RELEASE=<sha> bash deploy/smoke-headers.sh http://localhost:18081` | exit 0: 36 PASS, 0 FAIL, 1 SKIP (CSRF cookie, no gateway). `release` equals the commit sha |
| 4. CSRF / validation | `npm run api:negative` and the cucumber `--name "Soumission refusee en l'absence de jeton anti-CSRF"` run | `api:negative` exit 0, 20 skipped (no gateway). They cover every `InputValidator` rule. Cucumber 128/128 scenarios passed (the 403 comes from the gateway double) |
| 5. Audit record | `npm run check:audit`; each of the 6 headings removed in turn | exit 0 on the full file. Exit 1 with each heading removed. `git diff --exit-code` clean after restore |
| 5. DAST | `npm run dast` | 19 passed: 6 payloads, 78 states, **0 XSS finding** |
| 6. Probes | `curl /healthz`, `curl -i /version.json` | `/healthz` 200 in 138 ms from `docker run`. `version.json` has `release` = commit sha, an ISO `builtAt` and `Cache-Control: no-store` |
| Build | `npm ci && npm run lint && npm run build && npm test` | all exit 0. 242/242 tests, initial JS 92.23 kB gzip |

The image was built the same way as in section 2, because this workstation's Docker VM cannot
reach the private npm mirror or `dl-cdn.alpinelinux.org`. The runtime stage used the host-built
`dist/` on top of the `apk upgrade`d base layer. The CI build runs `Dockerfile` unchanged.
