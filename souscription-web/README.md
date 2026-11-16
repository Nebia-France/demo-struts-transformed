# souscription-web

Angular SPA for the souscription journey.

## Delivery vehicle

The **static container artifact** built from `Dockerfile` (multi-stage `node:22-alpine` build ->
`nginxinc/nginx-unprivileged`) replaces the Tomcat WAR as the delivery vehicle for the web tier.
The legacy WAR produced by the root `pom.xml` remains deployable and is the **rollback target
until plan 46** removes the Cargo/Tomcat profile.

## Build and run

```bash
npm ci
npm run lint
npm run build          # runs prebuild -> writes src/version.json, then ng build (production)
docker build -t souscription-web:local .
docker run --rm -p 18080:8080 souscription-web:local
```

`src/version.json` is generated (gitignored) by `scripts/write-version.mjs` from
`GIT_COMMIT_SHA`, and is served at `/version.json` as the deployed-version signal consumed by
telemetry.

## Cache policy (deploy/nginx.conf)

Two levels, replacing `SecurityFilter`'s blanket `Cache-Control: no-store`:

| Resource | Policy |
| --- | --- |
| Content-hashed assets (`*.js`, `*.css`, `*.woff2`, `*.png`, `*.svg`) | `public, max-age=31536000, immutable` |
| `/index.html`, `/version.json`, SPA fallback | `no-store` |

All `add_header` directives live in the single `server` context (the per-URI policy is resolved by
a `map`), because nginx does not inherit server-level `add_header` into a location that declares
its own — which would silently drop the security headers on the cached locations.

`/api/v1` JSON responses are `no-store`, emitted by the gateway, and the SPA adds no cache of its
own: no `HttpClient` response caching layer, no `shareReplay` under `src/app/api`, no service
worker and no `@angular/pwa`. `npm run check:no-sw` (wired into `lint`) fails the build if
`serviceWorker`, `@angular/service-worker` or `ngsw-config.json` appears, so subscription data can
never be replayed from a stale browser cache.

**Measurable criteria, asserted by `deploy/smoke-headers.sh`:**

- **0 kB of JS/CSS retransferred on a repeat visit** — hashed assets are `immutable`, so the
  browser does not even revalidate them; a conditional request with the matching
  `If-None-Match`/`If-Modified-Since` answers `304` with an empty body.
- **`index.html` always revalidated** — `/index.html` and every deep SPA route served by the
  `try_files $uri $uri/ /index.html` fallback (for example `/souscription/recapitulatif`) answer
  `no-store`, as does `/version.json`; a deployment is therefore picked up on the next visit.

## Security headers (deploy/nginx.conf, deploy/ingress.yaml)

The 5 headers formerly set by `src/main/java/fr/demo/souscription/web/SecurityFilter.java` are now
served by the edge. **Measurable criterion: the served header set is equivalent to or stricter
than those 5 headers, asserted automatically on every deployment** by
`deploy/smoke-headers.sh`, wired as a blocking step of
`.github/workflows/frontend-souscription-web.yml`.

| Header | Source (`SecurityFilter`) | Served |
| --- | --- | --- |
| `X-Content-Type-Options` | `nosniff` | `nosniff` |
| `X-Frame-Options` | `DENY` | `DENY` |
| `Referrer-Policy` | `no-referrer` | `no-referrer` |
| `Cache-Control` | `no-store` | `no-store` (hashed assets excepted, see above) |
| `Content-Security-Policy` | `default-src 'self'; style-src 'self'; img-src 'self'; script-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'` | same, with `script-src 'self'`, `connect-src 'self'` and a per-response nonce on `style-src` |
| `Strict-Transport-Security` | — | `max-age=31536000; includeSubDomains` (added) |

`script-src` is the only directive that is relaxed, from `'none'` to `'self'`: a SPA executes its
own content-hashed bundles. `'unsafe-inline'`, `'unsafe-eval'` and wildcard sources are forbidden —
an Angular production build needs none of them. `connect-src 'self'` suffices because the gateway
shares the SPA origin (OD-004).

Two consequences of this policy for the build, found by the behavioural gate (plan 40):

- Angular inserts each component stylesheet as a `<style>` element, which `style-src 'self'` alone
  blocks. `style-src` therefore also admits `'nonce-$request_id'`: nginx writes the same
  per-response value into `<app-root ngCspNonce="__CSP_NONCE__">` (`sub_filter`), and Angular
  stamps its `<style>` elements with it. Critical-CSS inlining is disabled in the production
  build (`optimization.styles.inlineCritical: false`) because it emits an inline `<style>` and an
  `onload` handler.
- `base-uri 'none'` voids any `<base href>`. `src/index.html` has none: the router base comes from
  `APP_BASE_HREF` and the build emits root-absolute resource URLs (`deployUrl: "/"`), so deep links
  such as `/produit/PRUDENT` load their bundles. The development configuration, never served with
  this policy, keeps `baseHref: "/"` for the dev server.

`npm run check:csp` fails when the nonce wiring is incomplete or a `<base>` element comes back;
`deploy/smoke-headers.sh` asserts that the header nonce equals the one in the served document and
changes on every response.

Two gates prevent a loosened policy:

- `npm run check:csp` (part of `npm run lint`) parses `deploy/nginx.conf` and fails the build on a
  missing directive, an `unsafe-*` source or a wildcard.
- `deploy/smoke-headers.sh <base-url>` asserts the complete set on a served response.
- The Gherkin scenario `Entetes de securite ajoutes a chaque reponse` asserts the same set on a
  served response; point it at the running artifact:
  `E2E_BASE_URL=http://localhost:18080 npx cucumber-js --config e2e/cucumber.cjs --name "Entetes de securite ajoutes a chaque reponse"`.

The same header set, except the Content-Security-Policy, is mirrored on `deploy/ingress.yaml` so the
headers are present whichever hop terminates the request. The CSP is not mirrored because its nonce
is generated per response by this container; a static copy at the ingress would replace it.

## Probes

- `GET /healthz` -> `200 ok` (liveness / readiness)
- `GET /version.json` -> `{"release": "<sha>", "builtAt": "<iso>"}`
- `GET /runtime-config.json` -> `{"telemetry": {"enabled": true, "collectorUrl": "<url>"}}`

Verify a deployment with `deploy/smoke-headers.sh https://host`.

## Telemetry and dashboards (plan 36)

The source tier produced no client-side signal: a failure after the page had been served was
invisible. `src/app/core/telemetry` is the replacement. `TelemetryService` beacons one payload
per signal to the collector of `/runtime-config.json`
(`navigator.sendBeacon`, falling back to `fetch(..., { keepalive: true })`, never retried), and
the whole layer is switched off by setting `telemetry.enabled` to `false` in that file — no
rebuild needed. The release carried by every payload is read from `/version.json` at bootstrap.

| Signal | `kind` | Fields |
| --- | --- | --- |
| Uncaught error (`ErrorHandler` + `unhandledrejection`) | `error` | sanitized message, `route`, `release` |
| Web Vitals on `/catalogue` and `/souscription/confirmation` | `vital` | `LCP`/`INP`/`CLS`, value, `route`, `release` |
| Journey events (step reached, step abandoned, confirmation succeeded) | `journey` | event name, `route`, `release` |

**No personal data ever leaves the browser:** the message is stripped of any object or array
fragment (the usual way a request body reaches a stack trace), of address and digit runs, and of
the subscriber field labels and the CSRF token name. `npm run check:privacy`
(`scripts/check-telemetry-privacy.mjs`, wired into `lint`) fails the build if a file under
`src/app/core/telemetry` names an identity field, the CSRF token, or serializes a body.

**Dashboards built on these payloads:**

- **Error volume** — count of `kind: error` per `release` and per `route`, which is the deployment
  regression signal (a release whose error rate rises is rolled back).
- **Web Vitals** — p75 of `LCP`, `INP` and `CLS` per `route`, compared with the thresholds of
  `docs/perf-profile.md`.
- **Journey completion rate** — `confirmation-succeeded` over `step-reached:catalogue`, with the
  `step-abandoned:<step>` counts giving the drop-off per step of the former `*.do` wizard.

## Correlation client -> passerelle (W7-T05)

Chaque appel sortant porte un entete `traceparent` au format W3C Trace Context
`00-<32 hex trace-id>-<16 hex span-id>-01`, genere par
`src/app/core/http/correlation.interceptor.ts` a partir de `crypto.getRandomValues`
(`src/app/core/http/traceparent.ts`). L'intercepteur est declare **en premier** dans
`httpInterceptorChain` de `src/app/app.config.ts`, consomme par `withInterceptors(...)` dans
`src/app/core/http.providers.ts` : une requete annulee par le timeout ou mappee par
`errorInterceptor` porte donc deja son entete au moment ou elle quitte le navigateur.

L'identifiant de trace de l'appel en cours est conserve dans un porteur a portee requete et
ajoute au champ `traceId` du payload de telemetrie, si bien qu'une erreur client est jointe a la
trace de passerelle de l'appel fautif. Le trace-id et le span-id sont exclusivement des octets
aleatoires : ni le jeton CSRF, ni une donnee personnelle, ni un identifiant de brouillon n'entre
dans l'entete ou dans le payload (`npm run check:privacy`).

### Criteres mesurables

- **100 % des erreurs client remontees** : `ErrorHandler` global et le hook
  `unhandledrejection` emettent un payload par erreur non rattrapee (plan 36).
- **100 % des appels API correles a une trace de passerelle** : l'entete `traceparent` est ajoute
  a toute requete sans exception, asserte par
  `src/app/core/http/correlation.interceptor.spec.ts` (format W3C, span-ids distincts entre deux
  appels, `traceId` du payload egal au trace-id de l'appel en echec).
- **Taux de jointure des traces suivi sur un tableau de bord** : part des payloads portant un
  `traceId` qui retrouve une trace de passerelle, par `release` et par `route` ; une baisse
  signale une perte de propagation entre le SPA et l'ingress.

## Ingress

`deploy/ingress.yaml` serves the SPA at `/` and proxies `/api/` to `souscription-gateway` under a
single host rule — same origin, so no CORS configuration is needed (decision OD-004).

## Contrôles propriété du gateway

`src/main/java/fr/demo/souscription/web/SecurityFilter.java` faisait trois choses : poser les
entêtes de sécurité, restreindre les méthodes HTTP et imposer l'encodage UTF-8. Selon
`TARGET_APPLICATION_TOPOLOGY.json`, `securityFilter` est attribué à **`souscription-gateway`** :
les deux contrôles suivants sont désormais la propriété du gateway et la SPA n'en est pas
responsable.

| Contrôle | Comportement d'origine (`SecurityFilter.java`) | Propriétaire cible |
| --- | --- | --- |
| Liste blanche des méthodes HTTP | toute méthode hors `GET`/`HEAD`/`POST` → `405` avec l'entête `Allow` | `souscription-gateway` (`methodAllowList` de `contracts/security-policy.json`) |
| Encodage UTF-8 des requêtes et des réponses | `request.setCharacterEncoding("UTF-8")` et encodage de réponse UTF-8 | `souscription-gateway` |

**C'est un transfert, pas un relâchement.** Le portail Gherkin `securite-web` doit continuer à
passer, exécuté au niveau du gateway (allocation du plan 39), avec le profil `all` qui lève le
filtre des scénarios alloués au SPA :

```bash
E2E_BASE_URL=<origine déployée> npx cucumber-js --config e2e/cucumber.cjs --profile all --name "Methodes HTTP non autorisees"
E2E_BASE_URL=<origine déployée> npx cucumber-js --config e2e/cucumber.cjs --profile all --name "Methodes HTTP autorisees"
E2E_BASE_URL=<origine déployée> npx cucumber-js --config e2e/cucumber.cjs --profile all --name "L'encodage des requetes et des reponses est UTF-8"
```

La liste blanche du contrat (`GET, HEAD, POST, PUT, DELETE, OPTIONS`) est plus large que le
`GET, HEAD, POST` historique parce que les actions `*.do` sont remplacées par des opérations REST
qui utilisent `PUT` (`putDraftSubscriber`, `putDraftSimulation`) et parce que `OPTIONS` est requis
par le protocole HTTP ; `PATCH`, `TRACE` et `CONNECT` restent refusés. `npm run check:methods`
échoue si `src/app/api/*.service.ts` émet un verbe autre que `get`, `post` ou `put`, si la liste
blanche du contrat ne couvre pas un verbe émis par la SPA, ou si elle accepte un verbe que le
filtre historique refusait.

### Validation client non autoritative

Les validateurs de `src/app/wizard/form-models.ts` existent uniquement pour le confort de saisie.
La règle fait foi côté serveur, dans
`src/main/java/fr/demo/souscription/service/InputValidator.java`, propriété des applications
backend ; le serveur revalide chaque soumission et ses `FieldErrors` l'emportent. Aucune règle
exclusivement navigateur n'est autorisée : `npm run check:methods` compare l'ensemble des
validateurs client à la liste des règles serveur et échoue si un validateur client n'a pas de
contrepartie.

`e2e/api/validation.spec.ts` le démontre **sans passer par l'interface** : un cas négatif par
règle d'`InputValidator` (versement initial 100–100 000 €, mensualité 0–2 000 €, durée 1–30 ans,
majorité 18 ans, longueurs 80/80/254, caractères de contrôle refusés), chacun attendant un `400`
avec la clé `FieldErrors` correspondante.

```bash
npm run api:negative            # nécessite un gateway joignable (API_BASE_URL)
npm run api:negative -- --list  # énumère les cas sans exécution
```

Cette suite est hors du portail par défaut (`lint`, `build`, `test`) ; elle est promue en travail
bloquant dans les plans 40 et 43.

### Modèle d'origine (même origine)

La SPA et le gateway sont servis depuis **une seule origine** derrière l'ingress partagé
(`deploy/ingress.yaml` : `/` → `souscription-web`, `/api/` → `souscription-gateway`). Il n'existe
donc aucun échange cross-origin et **aucune configuration CORS** n'est présente dans le dépôt :
`npm run check:no-cors` échoue si un entête `Access-Control` ou une directive CORS apparaît dans
`deploy/` ou `src/`. C'est aussi ce qui rend `SameSite=Strict` acceptable pour le cookie
`XSRF-TOKEN`.

Les trois services backend (`souscription-service`, `souscription-catalogue-service`,
`souscription-simulation-service`) ne sont pas joignables depuis l'extérieur du cluster : seul
`souscription-gateway` est routé par l'ingress, et les appels service à service sont authentifiés
en mTLS. Le serveur statique n'expose que les assets du build, `/healthz` et `/version.json` ;
tout autre chemin est rendu par le repli `index.html` et aucun listing de répertoire n'est servi
(`autoindex off` dans `deploy/nginx.conf`). `deploy/smoke-headers.sh` vérifie ces trois points sur
un déploiement.

## CI

`.github/workflows/frontend-souscription-web.yml` is the dedicated frontend pipeline. Since plan 41
it also runs `mvn test` at the repository root, which holds the backend tier of the Gherkin replay.

### Test pipeline (W8-T04): gate matrix

Every gate below is blocking. The pull-request gate stays fast; `main` runs the full set.

| Gate | Command | Pull request | `main` |
| --- | --- | --- | --- |
| Install | `npm ci` | yes | yes |
| Contract | `npm run contract:check`, `npm run api:verify` | yes | yes |
| Dependency audit | `npm audit --audit-level=high` | yes | yes |
| Lint and guards (incl. `check:allocation`, `check:no-pending`, `check:rate`, `check:audit`) | `npm run lint` | yes | yes |
| Build | `npm run build` | yes | yes |
| Unit + component | `npm test` | yes | yes |
| Accessibility | `npm run a11y` | yes | yes |
| Smoke journeys | `npm run e2e:journeys -- --grep "full-journey\|missing-csrf"` | yes | — (full run below) |
| All five journeys | `npm run e2e:journeys` | — | yes |
| Visual regression | `npm run visual` | — | yes |
| UI-bypassing negative API tests | `npm run api:negative` | — | yes |
| Consumer contract, 10 operations, resilience and security (plan 45) | `npm run contract:consumer` | — | yes |
| Gherkin replay, backend tier (Cucumber-JVM, 27 backend-allocated scenarios) | `mvn -q test` (repository root, JDK 8) | yes | yes |
| Gherkin replay, SPA/e2e tier (cucumber-js, 61 SPA-allocated scenarios) | `npm run e2e` | — | yes |
| Green-scenario gate (88/88, no exception) | `npm run check:rate` | — (via `lint`) | yes, after both tiers |
| Operational scenarios (resilience, replica loss, telemetry) | `npm run e2e:operational` | — | yes |
| First-load performance | `npm run perf:lh` | — | yes |
| DAST, reflected and stored XSS (`deploy/dast.md`) | `npm run dast` | yes | yes |
| Image scan, 0 HIGH/CRITICAL | `docker build` + `trivy image --severity HIGH,CRITICAL --exit-code 1` | yes | yes |
| Served security headers, `/healthz` within 5 s, `/version.json` release = commit sha | `docker build` + `EXPECTED_RELEASE=<sha> deploy/smoke-headers.sh` | yes | yes |

The audit of the delivered version (npm audit, image scan, DAST, headers, CSRF negative test,
UI-bypassing API validation, probes and version signal) is recorded in
[`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md). `npm run check:audit` fails the lint when one
of its six sections is missing or the DAST section does not report 0 XSS finding.

### Published reports

| Report | Produced by | Location in the workspace | CI artifact |
| --- | --- | --- | --- |
| Cucumber HTML / JSON | `npm run e2e` | `e2e/reports/cucumber.html`, `e2e/reports/cucumber.json` | `cucumber-report` (`main`) |
| Cucumber-JVM HTML / JSON (backend tier) | `mvn test` | `target/cucumber-reports/backend.{html,json}` | `cucumber-report` (`main`) |
| Operational cucumber HTML / JSON | `npm run e2e:operational` | `e2e/reports/cucumber-operational.{html,json}` | `cucumber-report` (`main`) |
| Green-scenario rate, per tier and per domain | `npm run e2e` (`scripts/scenario-rate.mjs`) | `e2e/reports/scenario-rate.json` (committed) | `cucumber-report` + job summary table (`main`) |
| Playwright HTML, one per suite | `a11y`, `e2e:journeys`, `visual`, `api:negative` | `playwright-report/<suite>/` (`PLAYWRIGHT_REPORT_DIR`) | `playwright-report` (`main`), `playwright-report-pr` (pull request) |
| Lighthouse | `npm run perf:lh` | `.lighthouseci/` | `lighthouse-report` (`main`) |
| DAST findings | `npm run dast` | `e2e/reports/dast.json`, `playwright-report/dast/` | `cucumber-report`, `playwright-report` (`main`) |
| Consumer contract measurements | `npm run contract:consumer` | `e2e/reports/contract-consumer.json`, `playwright-report/contract/` | `cucumber-report`, `playwright-report` (`main`) |

`e2e/reports/scenario-rate.json` (plan 41) merges the two reports of one pipeline run: the
cucumber-js report of `npm run e2e` for the 61 scenarios allocated to the SPA or e2e tier, and the
Cucumber-JVM report of `mvn test` (`target/cucumber-reports/backend.json`, or
`BACKEND_CUCUMBER_REPORT`) for the 27 allocated only to souscription-gateway or the backend
services. It holds `green` and `declared` (88), the per-tier counts (`tiers.spa`,
`tiers.backend`: scenarios green / allocated, example rows passed), the per-domain counts, the red
list and the outcome of every scenario. A scenario is green when the report of its allocated tier
holds it and every example row passed, hooks included. `npm run e2e` writes it whether the run
passes or fails and keeps cucumber-js' exit code, so run `mvn test` first.

`npm run check:rate` (`scripts/check-scenario-rate.mjs`, part of `lint`) fails when
`green < declared`, when a scenario of the allocation is absent, red, or replayed by another tier
than its allocated one, or when a domain is not at its declared total (catalogue 8,
validation-souscripteur 10, simulation 10, parcours-souscription 15, confirmation-souscription 13,
persistance-souscriptions 9, securite-web 11, initialisation-application 8,
formulaire-souscription 4). The file is committed so that `lint` checks the evidence of the last
full replay; `main` regenerates it from both tiers and runs `check:rate` again.

```bash
(cd ../.. && mvn -q test)   # backend tier, JDK 8
npm run e2e                 # SPA/e2e tier, then e2e/reports/scenario-rate.json
npm run check:rate          # 88/88 or fail
```

## Suite Gherkin (e2e)

`e2e/features/` holds a byte-for-byte copy of the 9 `.feature` files from the repository-root
`features/` directory (88 declared scenarios: 66 `Scenario:` + 22 `Scenario Outline:`, which
cucumber-js expands to 167 executable scenarios). The copy must stay byte-identical: the gate
selects scenarios with `--name` filters that match titles exactly.

```bash
npm run e2e:dry        # loads the features, no browser
npm run e2e            # behavioural gate: the 61 SPA-allocated scenarios (128 example rows), strict
npx cucumber-js --config e2e/cucumber.cjs --profile all   # every scenario, against E2E_BASE_URL
```

Since plan 40 `npm run e2e` is a **blocking gate** (`main`): the default profile of
`e2e/cucumber.cjs` runs exactly the scenarios whose tier in `e2e/SCENARIO_ALLOCATION.md` names the
SPA or e2e tier (`e2e/allocation.cjs` is the single parser of that table), in strict mode, so a
pending or undefined step fails the run. `npm run check:no-pending` (part of `lint`) also fails when
a step file referenced by an SPA-allocated scenario returns `'pending'`.

How the steps run:

- Unless `E2E_BASE_URL` is set, the run builds `dist/` when it is missing and serves it with
  `scripts/e2e-server.mjs`, which applies the `add_header`, cache `map`, `$request_id` nonce and
  `sub_filter` directives read from `deploy/nginx.conf`. Pointing `E2E_BASE_URL` at the container
  (`docker run -p 18080:8080 ...`) runs the same scenarios against nginx itself.
- Every scenario drives the production SPA in Chromium, in a fresh browser context.
- The SPA's `/api/v1` calls are answered by `e2e/support/fake-gateway.ts`, a stateful,
  contract-shaped test double of souscription-gateway porting the legacy rules (catalogue reference
  data, `InputValidator` with an injectable clock, `SimulationService`, confirmation-key rotation,
  idempotent confirmation, double-submit CSRF check, 405 for undeclared methods). The backend
  applications stay authoritative for those rules in their own acceptance suites.
- Legacy `*.do` paths of the Gherkin are mapped to the SPA routes of `src/app/app.routes.ts`; an
  HTTP status asserted on a browser-driven scenario is the one the SPA surfaced (`/erreur/:code`)
  or the gateway answer it rendered in place.

The 27 scenarios allocated only to backend applications (`persistance-souscriptions`,
`initialisation-application`, and the gateway-owned method, encoding and CSRF-rejection
scenarios) are replayed by the backend tier: `mvn test` at the repository root runs
`fr.demo.souscription.acceptance.BackendAcceptanceTest` (Cucumber-JVM 7.18.0 on the JUnit Platform
suite engine) on the untouched `features/` directory, against the legacy reference implementation
of this repository (`SecurityFilter` and the Struts actions in process, `SubscriptionRepository`
on H2 with fault injection, `Application` in an isolated class loader or in a child JVM on a
non-POSIX file system). `BackendAllocationTest` keeps its selection equal to the backend rows of
`e2e/SCENARIO_ALLOCATION.md`. Their cucumber-js step definitions remain available for a replay
against a deployed chain with `--profile all`.

`e2e/operational/` holds the operational scenarios backed by `e2e/steps/resilience.steps.ts` and
`e2e/steps/telemetry.steps.ts` (`npm run e2e:operational`, strict); they are not part of the 88
declared scenarios. `npm run check:no-pending` scans every `e2e/steps/*.steps.ts` file.

`e2e/SCENARIO_ALLOCATION.md` allocates each of the 88 scenarios to the tier that asserts it
(SPA, browser e2e, gateway or a backend service) and names the test file. It also records how the
6 declared coverage gaps are handled. `npm run check:allocation` (part of `lint`) fails when a
`.feature` title is missing from the allocation, when an allocated title no longer exists, or when
the total is not 88.

## Cadence de version

- **Stay on a supported Angular major.** Upgrade before the current major leaves Angular's active
  support window; never let the SPA run on an unsupported major.
- **`ng update` is the only upgrade path.** Angular, the CLI, the build package and
  `angular-eslint` are upgraded together via `ng update`; do not hand-edit their versions in
  `package.json`.
- **Minimal dependency surface.** Runtime dependencies are limited to Angular packages, `rxjs`
  and `tslib`. Any new runtime dependency requires an explicit justification.
- **No UI component library.** Components are built in-house against the project's own styles, so
  no third-party design system dictates the upgrade cadence.
- **Automated dependency PRs.** `.github/dependabot.yml` opens weekly npm PRs for
  `/frontend/souscription-web`; patch and minor bumps are merged once the frontend pipeline is
  green.

## Struts -> Angular

Once the legacy tier is removed (plan 46), this table is the only record of where each Struts
concept went. `npm run check:no-struts` (`scripts/check-no-struts-ref.mjs`, part of `lint`) fails
if any file under `src/` or `deploy/` still references a `.do` path, `struts`, a `.jsp` or a
`.jspf`: legacy names are documented here, never in the SPA sources.

| Struts concept | Angular target |
| --- | --- |
| action mapping (`struts-config.xml`, `*.do` servlet mapping) | route of `src/app/app.routes.ts` / `src/app/wizard/wizard.routes.ts` (see "Route correspondence") |
| `ActionForm` (`SubscriptionForm`) | typed reactive form (`src/app/wizard/form-models.ts`, defaults in `form-defaults.ts`) |
| forward / global-forward / redirect-after-post | `Router` navigation after the API call (`WizardFacade`, `ConfirmService`) and route guards (`wizard.guards.ts`) |
| `error-page` of `web.xml` (`erreur.jsp`) | `/erreur/:code` (`ErrorPageComponent`), `**` -> `/erreur/404`, `GlobalErrorHandler` |
| session attribute (`HttpSession` subscription draft) | server-side draft (`/drafts` of the gateway API, persisted by `souscription-service`) + signal store (`src/app/wizard/draft.store.ts`) |
| `SecurityFilter` | edge headers (`deploy/nginx.conf`, `deploy/ingress.yaml`) + Angular built-in XSRF (`withXsrfConfiguration`) + gateway controls (see "Contrôles propriété du gateway") |

The JSP views map to components: `catalogue.jsp` -> `CatalogueListComponent`, `produit.jsp` ->
`ProductDetailComponent`, `souscripteur.jsp` -> `SubscriberStepComponent`, `simulation.jsp` ->
`SimulationStepComponent`, `recapitulatif.jsp` -> `SummaryStepComponent`, `confirmation.jsp` ->
`ConfirmationStepComponent`, `erreur.jsp` -> `ErrorPageComponent`; the fragments
`header.jspf` / `footer.jspf` -> `AppLayoutComponent` (header, footer, breadcrumb, step rail),
`errors.jspf` -> `ErrorSummaryComponent`, `result.jspf` -> `SimulationResultComponent`,
`csrf.jspf` -> Angular built-in XSRF header (`withXsrfConfiguration`, no hidden field).

### Route correspondence

Target inventory: six feature routes plus the error route, each screen with its
component-scoped SCSS. `src/app/app.routes.spec.ts` asserts one row each.

| Struts source | Angular target |
| --- | --- |
| `catalogue.do` (welcome-file) | `/catalogue`, redirected from `''` |
| `produit.do?code=X` | `/produit/:code` (`produit` without a code -> `/erreur/404`) |
| `commencer.do` (POST) | `startDraft` + navigate `/souscription/souscripteur` |
| `souscripteur.do` | `/souscription/souscripteur` |
| `simulation.do` | `/souscription/simulation` |
| `recapitulatif.do` | `/souscription/recapitulatif` |
| `confirmer.do` (POST) | `confirmDraft` + navigate `/souscription/confirmation` |
| `confirmation.do` | `/souscription/confirmation` |
| `catalogue.do?notice=parcours` | `/catalogue?notice=parcours` |
| unknown path -> `sendError(404)`, `error-page` | `/erreur/:code`, `**` -> `/erreur/404` |

The two POST mappings have no route: they are an API call followed by a navigation.

## Label inventory

`npm run check:labels:inventory` (`scripts/label-inventory.mjs`, part of `lint`) regenerates
`docs/LABEL_INVENTORY.md`: every SPA label, the `labels.ts` module that owns it and the legacy
view it was ported from. It fails when a template renders a literal text node or a literal
`aria-label` / `title` / `placeholder` / `alt` instead of a label, or when a label re-declares a
string owned by the backend tier (validation messages arrive through `FieldErrors` and are
rendered verbatim, never duplicated client-side). The legacy view texts and backend strings are
snapshotted into `docs/legacy-text-inventory.json` while the legacy sources exist, so the
comparison still runs after plan 46.

## Contrat vendored (`contracts/`)

`contracts/openapi.yaml` is a byte-for-byte copy of
`global/contracts/souscription-web-to-souscription-gateway/openapi.yaml`, the OpenAPI 3.1.0
contract published by `souscription-gateway`. It is the single source of truth for the SPA's
HTTP types and calls; the eight legacy `*.do` Struts action mappings were never a contract.
`contracts/security-policy.json` and `contracts/resilience-contract.json` are copied from the same
directory and are consumed by later plans.

Compatibility rules:

- `/api/v1` is **public** and is the only surface the SPA may call.
- `/v1` is **internal** between backend services and must never be called from the SPA.
- Only backward-compatible additions are permitted within `v1`; a breaking change requires a new
  major version path.
- The SPA must never reintroduce `*.do` paths.

```bash
npm run contract:check   # vendored copy vs published contract, 10 operationIds, no *.do in src/
```

`contract:check` also requires `shared-schemas/` to be byte-identical to
`global/contracts/shared-schemas/`, since the consumer contract suite validates payloads against them.

```bash
npm run contract:consumer   # 10 operations validated with Ajv against the contract schemas,
                            # monetary fields as strings, resilience + security policy checks
CONTRACT_BASE_URL=https://staging.horizon-souscription.example.com npm run contract:consumer
npm run check:contract      # docs/CONTRACT_VALIDATION.md complete, production stub flag "false"
```

Without `CONTRACT_BASE_URL` the suite runs against the gateway double on the production build and
annotates every test `target: gateway-double`; with it, nothing is intercepted and the deployed
provider answers. The measured values are recorded in
[`docs/CONTRACT_VALIDATION.md`](docs/CONTRACT_VALIDATION.md); `npm run check:contract` (in `lint`)
fails when one of its four sections (operations, resilience, security, versioning) is missing.

`contract:check` runs first in `npm run lint` and as a blocking step in the frontend pipeline
right after `npm ci`. When the gateway contract changes, re-copy the published file — do not edit
the vendored copy by hand.

## Couche HTTP (`src/app/api/`)

Four hand-written, stateless `HttpClient` services cover the ten operations of the vendored
contract. They replace the POST/redirect/GET HTML cycle of the eight Struts `*.do` actions and
hold no business state (the signal store is the sole owner).

| Service | Operations |
| --- | --- |
| `CsrfApiService` | `issueToken` (`issueCsrfToken`) |
| `CatalogueApiService` | `listProducts`, `getProduct` |
| `SimulationApiService` | `simulate` |
| `SubscriptionApiService` | `startDraft`, `getDraft`, `putDraftSubscriber`, `putDraftSimulation`, `confirmDraft`, `getSubscription` |

`src/app/api/api-base-url.ts` holds the only base URL, `/api/v1`: relative on purpose, since the
SPA and the gateway share one origin behind the ingress (OD-004). No absolute URL appears in
`src/app/api`. Every path segment interpolated from a variable goes through `encodeURIComponent`.
`HttpClient` is provided per route by `src/app/core/http.providers.ts`, which also applies the
double-submit XSRF configuration declared in `src/app/app.config.ts`.

### Consumed contract status

| | |
| --- | --- |
| Provider | `souscription-gateway` |
| Contract | `global/contracts/souscription-web-to-souscription-gateway/openapi.yaml` (vendored as `contracts/openapi.yaml`) |
| Provider deployed | no — the consumer contract suite (`npm run contract:consumer`) runs against the gateway double until `CONTRACT_BASE_URL` points at the deployed gateway |
| Stub mechanism | Mock Service Worker (`src/app/api/testing/handlers.ts`, `browser.ts`) |
| Switch marker | `NG_APP_USE_GATEWAY_STUB`, substituted by the `define` option of `angular.json` (`true` in the development configuration, `false` in production) and read at the `CONTRACT STUB SWITCH` marker in `src/main.ts` |

The stub answers all ten operations with contract-shaped bodies (amounts and the annual rate as
decimal strings, dates ISO-8601). It runs only in the development configuration: the production
build folds the flag to `'false'`, so the dynamic import is removed and `msw` never reaches the
bundle. `public/mockServiceWorker.js` is generated by `npx msw init public/ --save` and is copied
to the output only by the development configuration.

Set `NG_APP_USE_GATEWAY_STUB` to `"false"` in the development configuration of `angular.json`
once `souscription-gateway /api/v1` is deployed; `npm start` then proxies `/api` to it through
`proxy.conf.json`.

### Unit tests

`npm test` runs `ng test` (`@angular/build:unit-test`, Vitest runner, jsdom environment).
`src/app/api/*.spec.ts` asserts, for each of the four services, the URL, the HTTP method, the
request body, path-segment encoding (`getProduct('a/b')` -> `products/a%2Fb`) and error
propagation, using `HttpTestingController`.

## Resilience (`src/app/core/http/`)

`timeoutInterceptor` (`src/app/core/http/timeout.interceptor.ts`) is registered through
`httpProviders` (`src/app/core/http.providers.ts`), which feature routes add to their own
`providers`, and enforces `contracts/resilience-contract.json`. `HttpClient` is deliberately not
provided in the root configuration: it is added by the catalogue and wizard routes only. The
production `initial` budget of `angular.json` is 213 kB warning / 215 kB error (current initial
total: 212.19 kB, estimated transfer size 59.87 kB). This is an explicit amendment of the previous
190/200 kB thresholds, which were measured with an empty route table (187.79 kB). Activating the
Router configuration that replaces `struts-config.xml` brings the `@angular/router` chunk
(85.62 kB raw: route matching, redirects, lazy loading, `RouterOutlet`) into the initial bundle,
on top of `@angular/core` (105.16 kB) and `rxjs` (20.44 kB); no application code remains in the
initial graph beyond `main` (929 bytes) — every feature route, its `HttpClient` providers and its
resolvers are lazily loaded (`catalogue.routes.ts`, `wizard.routes.ts`). The initial bundle
therefore cannot be brought under 200 kB raw without dropping the Router itself, so the threshold
is raised to the measured size plus a small margin rather than the budget being disabled.

Since the parity check of W7-T01, `/catalogue` (and `catalogue-list.component`) is **eager**: it is
the default landing route (`''` redirects to `catalogue`) and the route whose LCP is measured, so no
chunk request sits between bootstrap and the first paint. Keeping it eager pulls `httpProviders`,
`catalogue.resolvers.ts` and the `CatalogueApiService` graph into the initial bundle, which raises
the raw initial total; the 200 kB **gzip** criterion stays largely met (89.38 kB), and the raw
`initial` budget of `angular.json` is amended accordingly, as **tracked debt** (see
"Budget de premiere charge" below). `/produit` and the five wizard steps remain lazy.

The catalogue screens of `catalogue.jsp` / `produit.jsp` render the annual rate with the `number`
pipe (the former `fmt:formatNumber maxFractionDigits="0"`). Although both components are lazy, the
`@angular/common` i18n number-formatting code they pull in is hoisted into the shared initial
chunk, since `@angular/common` already belongs to it: the initial total goes from 251.05 kB to
264.08 kB. The `initial` budget is therefore amended once more to 270 kB warning / 275 kB error,
again the measured size plus a small margin. Dropping the pipe is not an option: it is the only
locale-aware formatting of the rate.

W5-T04 adds `GlobalErrorHandler`, its `TelemetryService` stub and the `unhandledrejection` hook
to the initial graph — they are the catch-all that replaces the `<error-page>` forwards, so they
cannot be lazy. The initial total goes from 274.86 kB to 275.48 kB and the `initial` budget is
amended to 276 kB warning / 280 kB error, again the measured size plus a small margin.

| Threshold | Value | Enforced by |
| --- | --- | --- |
| Client timeout | exactly 10 s (`TIMEOUT_MS = 10_000`) | `timeout({ each: TIMEOUT_MS })` |
| Retries | 0, on any method including mutating ones | `npm run check:no-retry` (fails the build on `retry(`, `retryWhen(`, `retryBackoff` under `src/app/api` and `src/app/core/http`) |
| Circuit breaker | disabled | — |
| Concurrent calls per origin | at most 6 (`BULKHEAD_LIMIT`) | `withBulkhead()` in `src/app/core/http/concurrency.ts` |
| Provider latency budget | p95 <= 600 ms, p99 <= 1500 ms | provider-side Micrometer timers |
| Availability objective | 99.9% (OD-009) | — |

On a timeout the SPA surfaces the gateway's problem+json message and keeps the wizard state in
its signal store; a confirmation can be re-submitted safely because it is idempotent on
`confirmationKey`.

`src/app/core/http/timeout.interceptor.spec.ts` asserts the 10 000 ms boundary and that exactly
one HTTP request is issued. `e2e/operational/resilience.feature` (steps in
`e2e/steps/resilience.steps.ts`, `npm run e2e:operational`) injects a 12 s gateway latency in the
browser and asserts the client abort at 10 s, a single entry in the gateway access log and at most
6 calls in flight.

## Classification des erreurs HTTP (`src/app/core/http/error.interceptor.ts`)

`errorInterceptor` est le dernier maillon de la chaine (`XSRF integre -> timeout -> error`,
declaree une seule fois dans `src/app/app.config.ts`). Il traduit chaque echec de transport ou reponse
`application/problem+json` de la passerelle en une des cinq familles de `AppError`
(`src/app/core/error/app-error.ts`), de sorte qu'aucun consommateur ne relit un code HTTP.

| Reponse | Famille | Comportement | Magasin (`DraftStore`) |
| --- | --- | --- | --- |
| statut 0 (hors ligne, requete annulee, CORS) | `network` | message reessayable sur place, saisie conservee | conserve |
| 400 avec un tableau `errors` non vide | `field` | messages reprojetes sur le formulaire, **jamais** de navigation vers une page d'erreur | conserve |
| 404, 410 | `expired` | `DraftSyncService` vide le magasin et redirige vers `/catalogue?notice=parcours` | **vide volontairement** |
| 409 | `conflict` | `ConfirmService` reaffiche la confirmation existante (rejeu idempotent sur `confirmationKey`) | conserve |
| tout autre statut (400 sans champ, 403, 405, 500, 503...) | `http` | navigation vers `/erreur/:status`, sans detail technique rendu | conserve |

Seule la famille `expired` vide le magasin : un appel en echec ne detruit jamais la saisie de
l'utilisateur. Le `detail` du document problem+json est transporte dans la famille `http` mais
n'est pas affiche : `erreur.jsp` n'exposait aucune trace technique et `ERROR_MESSAGES`
(`src/app/core/error/error-catalog.ts`) reste la seule source des libelles.

L'interception n'ajoute aucun rejeu (`npm run check:no-retry`) : l'erreur est classee puis
relancee telle quelle. `src/app/core/http/error.interceptor.spec.ts` couvre les statuts 0, 400
avec champs, 400 sans champ, 403, 404, 405, 409, 410, 500 et 503, et verifie qu'aucune requete
n'est rejouee. Les resolveurs de catalogue (`src/app/catalogue/catalogue.resolvers.ts`) routent un
chargement en echec vers `/erreur/:status`, ce que faisaient les `<error-page>` de `web.xml`.

## Synchronisation du brouillon (`src/app/wizard/draft-sync.service.ts`)

`DraftSyncService` remplace les mutations de session du wizard legacy
(`session.setAttribute("draft", ...)` sur `/commencer`, puis une mutation par etape) par trois
appels a la table de brouillon persistee par `souscription-service`, exposee par la passerelle :

| Transition | Appel |
| --- | --- |
| Entree dans le parcours depuis un produit (ancien `/commencer`) | `startDraft` -> `POST /api/v1/drafts` |
| Fin de l'etape 2 | `putDraftSubscriber` -> `PUT /api/v1/drafts/{id}/subscriber` |
| Fin de l'etape 3 (ancien `command=continuer`) | `putDraftSimulation` -> `PUT /api/v1/drafts/{id}/simulation` |

La reponse du serveur est **autoritaire** : le store n'est alimente que par le `DraftView`
retourne, jamais par des champs inventes cote client. Les mutations sont serialisees par
`concatMap` sur une file unique, donc deux soumissions d'etape ne peuvent jamais s'executer en
parallele sur le meme `draftId` (complete par le plan 18). La seule ecriture navigateur est la
reference de brouillon (`saveDraftRef` : `draftId` + `createdAt` en `sessionStorage`).

- Aucune donnee de parcours cote client : `npm run check:no-localstorage`
  (`scripts/check-no-local-journey.mjs`) echoue si `localStorage` apparait sous `src/`, et le
  script est enchaine dans `npm run lint`.
- Critere mesurable (chaos, plan 41) : **N repliques sans affinite de session, 0 perte de
  parcours** / *N replicas without session affinity, 0 journey loss* lorsqu'une replique est
  arretee en cours de parcours (aucune perte de parcours). Verifie par
  `e2e/operational/resilience.feature` (`npm run e2e:operational`) : 3 instances de
  `scripts/e2e-server.mjs` derriere un repartiteur tourniquet sans affinite, une instance arretee
  apres l'etape 2, le parcours reprend jusqu'au recapitulatif sur le meme brouillon serveur.
- `src/app/wizard/draft-sync.service.spec.ts` verifie, avec `HttpTestingController`, un seul
  appel HTTP par transition, la methode et l'URL de chaque operation, la mise a jour du store
  depuis le corps de la reponse, et que deux `saveSubscriber` rapides produisent deux requetes
  **sequentielles**.

## Sequencement du parcours (`src/app/wizard/wizard.guards.ts`)

Les trois `CanActivateFn` reproduisent la cascade de `SubscriptionAction` dans le meme ordre :
`draft == null` -> `/catalogue?notice=parcours` (ancien `restart()`), `subscriber == null` ->
`/souscription/souscripteur`, `simulation == null` -> `/souscription/simulation`.

| Route (`wizard.routes.ts`) | `canActivate` | Niveaux verifies |
| --- | --- | --- |
| `souscripteur` | `requireDraft` | brouillon |
| `simulation` | `requireSubscriber` | brouillon, souscripteur |
| `recapitulatif` | `requireSimulation` | brouillon, souscripteur, simulation |
| `confirmation` | `requireSimulation` | brouillon, souscripteur, simulation |

Ces gardes sont **non autoritaires** : ce sont de simples commodites d'experience utilisateur
qui evitent d'afficher une etape inatteignable. L'etat du brouillon reste detenu par le serveur,
et **chaque etape franchie est revalidee par l'API** a la soumission (`souscription-service`
rejette toute transition dont les prerequis ne sont pas remplis, independamment de ce que le
SPA a autorise). Un contournement de la garde cote navigateur ne peut donc pas faire avancer
le parcours.

La cible de redirection du cas « sans brouillon » est exactement `/catalogue?notice=parcours` :
le parametre `notice` est observe par le scenario Gherkin
« Reprise du parcours sans brouillon en session ». `src/app/wizard/wizard.guards.spec.ts`
couvre les 4 routes gardees sur chaque niveau de la cascade et verifie l'`UrlTree` retourne.

## Route data loading (`src/app/catalogue/catalogue.resolvers.ts`)

The source rendered `catalogue.jsp` and `produit.jsp` server-side, after `CatalogAction` had put
the products on the request: the markup could not be sent before the catalogue read had completed.
The SPA replaces that single server round trip with two functional resolvers:

| Resolver | Type | Gateway call |
| --- | --- | --- |
| `catalogueResolver` | `ResolveFn<ProductSummary[]>` | `GET /api/v1/products` |
| `productResolver` | `ResolveFn<ProductDetail>` | `GET /api/v1/products/{code}` (code taken verbatim from the `code` route parameter, encoded by `CatalogueApiService`) |

The router starts the resolver request while the SPA is still bootstrapping, so the fetch overlaps
application start-up instead of following it. Resolvers are functional only (`ResolveFn` +
`inject()`); no `Resolve` class is used, and `grep -rn "class .*Resolve" src` must stay empty.

No cross-origin `preconnect` hint is added: the gateway shares the SPA origin behind the shared
ingress, so the connection is already established.

`src/app/catalogue/catalogue.resolvers.spec.ts` asserts that `catalogueResolver` calls
`listProducts()` exactly once and that `productResolver` forwards the `code` parameter verbatim,
including a code needing URL encoding.

### Performance gate

`lighthouserc.json` runs Lighthouse CI over `/catalogue` and `/souscription/recapitulatif` on a
Slow 4G / 4x CPU slowdown mobile preset and asserts `largest-contentful-paint <= 2500` ms.
It is run with `npm run perf:lh` and is deliberately absent from `npm run lint` and
`npm test`: it needs a served production build. Since W7-T01 it is a **blocking** CI step
(`perf:lh` in `.github/workflows/frontend-souscription-web.yml`, run right after `npm run build`),
so a regression of the largest contentful paint on either route fails the pipeline.

## Budget de premiere charge et precompression (W7-T01)

La source servait un aller-retour HTML avec ~0 kB de JS. Les criteres mesurables de la cible :

| Critere | Valeur | Applique par |
| --- | --- | --- |
| JS initial | <= 200 kB **gzip** — mesure : 89,38 kB gzip (somme des variantes `.gz` des 12 chunks initiaux) pour 285,35 kB brut | `scripts/check-initial-budget.mjs`, enchaine dans `npm run build` (echec de build au-dela) |
| LCP | <= 2,5 s en Slow 4G / CPU 4x mobile, sur `/catalogue` et `/souscription/recapitulatif` | `lighthouserc.json`, etape bloquante `perf:lh` |
| Amorcage du SPA | cible 0,3–1,0 s sur materiel milieu de gamme | mesure Lighthouse (LCP ci-dessus) |

Le budget `initial` d'Angular se mesure en **taille brute**, pas en gzip : le critere de 200 kB gzip
est donc applique par `scripts/check-initial-budget.mjs`, qui somme les variantes `.gz` des scripts
initiaux de l'`index.html` emis et fait echouer le build au-dela. Le budget brut d'`angular.json`
reste un garde-fou secondaire, exprime a 292 kB avertissement / 296 kB erreur (releve de
286/290 en W7-T05 pour le code de correlation `traceparent`, le critere gzip restant l'arbitre) — **dette suivie**,
justifiee plus haut par le poids de `@angular/core`, `@angular/router`, `@angular/common` i18n,
`GlobalErrorHandler` et la route `/catalogue` rendue eager pour le LCP. Le critere gzip est, lui,
largement tenu : 89,38 kB.

Depuis W9-T02, `withPreloading(PreloadAfterLandingStrategy)` (necessaire pour tenir le p95 de
navigation entre etapes) porte le bundle initial brut a 292,25 kB, soit 250 octets au-dessus de
l'avertissement de 292 kB (le build passe, l'erreur a 296 kB n'est pas atteinte). Le seuil n'est
**pas** releve : cet avertissement est une **dette suivie** (deviation 10 de `docs/BENCHMARK.md`),
et le critere gzip reste l'arbitre (91,95 kB gzip). Depuis W9-T04, la ligne de version du pied de
page ajoute 0,58 kB : 292,83 kB brut et 92,23 kB gzip.

Decoupage verifie sur la sortie de production : `catalogue` (route d'atterrissage, dont le LCP est
mesure) fait partie du bundle initial, `produit` est paresseux, et les cinq etapes du parcours sont
chargees paresseusement une par une (`wizard-routes`, `subscriber-step-component`,
`simulation-step-component`, `summary-step-component`, `confirmation-step-component`), parmi plus
de 20 chunks paresseux. Aucun `preconnect` cross-origin n'est ajoute (la passerelle partage
l'origine) et la feuille globale est deja inlinee en CSS critique par le build, donc aucun
`<link rel="preload" as="style">` n'est necessaire dans `src/index.html`.

`npm run build` enchaine `ng build --configuration production`, `node scripts/precompress.mjs`
puis `node scripts/check-initial-budget.mjs`. La precompression ecrit un `.gz` (niveau 9) a cote
de chaque `.js`/`.css` de `dist/souscription-web/browser`. `deploy/nginx.conf` active
`gzip_static on` pour servir ces variantes telles quelles. Brotli n'est pas servi : ni les depots
Alpine configures dans l'image `nginxinc/nginx-unprivileged:stable-alpine` ni nginx.org ne
publient de module brotli pour ce build nginx, si bien que l'installation du `Dockerfile` echouait
(constat W9-T02, voir `docs/BENCHMARK.md`).

`scripts/perf-server.mjs` est le harnais de mesure utilise par `lighthouserc.json`
(`startServerCommand`) : il sert `dist/souscription-web/browser` avec le repli SPA, **la meme
negociation `.gz`** que nginx, et repond aux deux seules lectures dont depend le premier
rendu (`GET /api/v1/csrf-token` attendu par `provideAppInitializer`, `GET /api/v1/products`
attendu par `catalogueResolver`). Sans ces deux reponses l'initialiseur echoue et Lighthouse
rapporte `NO_FCP` ; sans la negociation, la mesure modeliserait des transferts non compresses.
Ce serveur n'est jamais deploye. Mesures de reference : LCP 2 110 ms sur `/catalogue` et
2 108 ms sur `/souscription/recapitulatif`.

Le benchmark contre la reference JSP (W9-T02) est consigne dans `docs/BENCHMARK.md` :
`npm run perf:lh` (LCP, JS initial), `npm run perf:steps` (p95 clic-vers-rendu par etape, tas JS,
taches longues, confirmation unique), `npm run perf:load` (p95/p99 sous charge,
`TARGET=<origine>`).

## Fenetre de validite du brouillon (`src/app/wizard/draft-window.ts`)

`src/main/webapp/WEB-INF/web.xml` declarait `<session-timeout>20</session-timeout>` : passe ce
delai, la session etait detruite et l'utilisateur renvoye au catalogue. La cible reproduit le meme
comportement observable sans session serveur :

| Element | Valeur |
| --- | --- |
| `DRAFT_WINDOW_MS` | `20 * 60 * 1000` (20 minutes), valeur unique de la fenetre |
| Fenetre de purge du brouillon serveur (`souscription-service`) | **20 minutes**, identique par accord explicite ; les deux valeurs doivent rester alignees |
| Cible de redirection a l'expiration | `/catalogue?notice=parcours` (`EXPIRY_REDIRECT_URL`) |

- `DraftStore.isStale()` s'appuie sur `isExpired(createdAt)` : `hasDraft()` devient faux a partir de
  `DRAFT_WINDOW_MS`, donc `requireDraft` redirige vers `/catalogue?notice=parcours`, exactement le
  comportement d'expiration de session d'hier.
- `DraftStore.expireIfStale()`, appele par les gardes, vide le store **et** la reference
  `sessionStorage` : un rechargement ne peut pas ressusciter un parcours expire.
- Une reponse **404 ou 410** de `getDraft` (le serveur a purge le brouillon) est traitee comme une
  expiration, jamais comme une erreur technique : au bootstrap (`app.config.ts`) comme dans
  `DraftSyncService.reload()`, le store et la reference sont vides et l'utilisateur atterrit sur
  `/catalogue?notice=parcours`. Les autres statuts restent des erreurs et remontent a l'appelant.

### Criteres mesurables

1. Le store ne reprend jamais un parcours au-dela de la fenetre : `hasDraft()` est vrai a
   `DRAFT_WINDOW_MS - 1` ms et faux a `DRAFT_WINDOW_MS` (voir `draft-window.spec.ts` et
   `draft.store.spec.ts`).
2. La table des brouillons serveur reste bornee : les lignes abandonnees sont purgees dans la
   fenetre configuree, et le nombre de lignes est suivi dans le temps (metrique de
   `souscription-service`).

## Idempotence de la confirmation (`src/app/wizard/confirm.service.ts`)

L'action Struts `confirmer` comparait `draft.getConfirmationKey()` a la cle soumise dans un
`synchronized (session)`, repondait 409 en cas d'ecart, puis redirigeait apres POST. La cible
conserve les trois invariants sans session serveur :

| Invariant source | Mecanisme cible |
| --- | --- |
| Une souscription par cle de confirmation | la cle vient du brouillon serveur (`DraftStore.confirmationKey()`), rejouee telle quelle ; jamais derivee cote client |
| Pas de rejeu par le bouton Retour | `router.navigate([...], { replaceUrl: true })` (equivalent du redirect-after-post) |
| Pas de double soumission | `inFlight` court-circuite le second appel et desactive le bouton de soumission |
| 409 sur cle erronee | `conflict()` expose « Demande deja traitee. », classe en conflit et non en erreur technique |

- Apres confirmation, `DraftStore.complete()` conserve `reference` et `confirmationKey` : une
  seconde soumission avec la meme cle reaffiche la confirmation existante sans nouvelle requete.
- Toute modification du souscripteur apres la simulation invalide la cle cote serveur ; la nouvelle
  cle est reprise du `DraftView` retourne.
- Les mutations de brouillon sont serialisees par la file `concatMap` de `DraftSyncService`, qui
  remplace le verrou de session ; chaque etape expose son propre signal en vol
  (`startInFlight`, `subscriberInFlight`, `simulationInFlight`) pour desactiver son bouton.

### Criteres mesurables

1. p95 du clic a l'affichage <= 500 ms par etape du parcours.
2. 0 souscription dupliquee en double soumission : un double clic rapide n'emet qu'une seule
   requete `confirmDraft` (voir `confirm.service.spec.ts`).

### Gestion globale des erreurs (W5-T04 / W5-T05)

`GlobalErrorHandler` (`src/app/core/error/global-error.handler.ts`) est enregistre comme
`ErrorHandler` dans `app.config.ts` et recoit aussi les rejets de promesses non traites via le
hook `unhandledrejection` pose dans `src/main.ts`. Toute erreur qui echappe a un composant est
signalee une fois a `TelemetryService` (stub no-op jusqu'au plan 36) puis routee vers
`/erreur/:status` en `skipLocationChange` : aucun ecran ne reste blanc, ce qui reproduit les 7
`<error-page>` de `web.xml`.

Les echecs rendus **dans la page**, saisie conservee, ne passent jamais par ce handler :

| Echec | Rendu | Reprise |
| --- | --- | --- |
| `field` | messages sur les champs (`errors.jspf`) | correction puis resoumission |
| `conflict` (409) | message en tete du recapitulatif | reaffichage de la confirmation |
| `http` 503 sur le recapitulatif | `ERROR_MESSAGES['503']` dans `#storage-error` | bouton « Reessayer » |
| `network` (dont le timeout 10 s) | `ERROR_MESSAGES['network']` dans `#storage-error` | bouton « Reessayer » |

Chacune des quatre etapes expose `status: 'idle' | 'pending' | 'error' | 'success'` ; `pending`
desactive le bouton de soumission et porte `aria-busy` sur le formulaire.

Criteres mesurables de la gestion d'erreur : p95 du clic a l'affichage <= 500 ms par etape ;
0 confirmation dupliquee en double soumission.

## Portage de `application.css` (design system SCSS)

La feuille globale legacy `src/main/webapp/assets/css/application.css` (107 lignes) a ete
decoupee en trois niveaux : le global minimal (`src/styles.scss`), les jetons partages
(`src/styles/_tokens.scss`) et les partiels/stylesheets de composants. Les selecteurs
descendants qui traversaient une frontiere de composant (`.panel-body p`, `.message p`,
`.error ul`, `.acceptance label`...) ont ete reecrits en selecteurs locaux dans le partiel
du groupe concerne ; `::ng-deep` n'est utilise nulle part.

Jetons (`src/styles/_tokens.scss`) : `$c-brand-dark #153c64`, `$c-text #202c38`,
`$c-page #e6e9ed`, `$c-link #003b7a`, `$c-link-visited #60406f`, `$c-link-hover #8d2431`,
`$c-focus #1b5794`.

| Groupe de regles source | Destination | Niveau |
| --- | --- | --- |
| `*`, `html`, `body`, `a`, `a:visited`, `a:hover`, `:focus-visible`, `.site`, `.sr-only`, `.skip-link`, `body` @760px | `src/styles.scss` | global |
| `.utility`, `.masthead`, `.brand`, `.brand-mark`, `.brand strong`, `.brand div > span`, `.space-title`, `.tabs`, `.tabs > a/span`, `.tabs .selected` (+ media 760/520) | `src/app/layout/site-header.component.scss` | layout |
| `.workspace`, `.sidebar`, `.sidebar h2`, `main`, `h1`, `p` (+ media 760/520) | `src/app/layout/app-layout.component.scss` | layout |
| `.steps`, `.steps li`, `.steps li.current`, `.sidebar h2` du wizard (+ media 520) | `src/app/layout/wizard-steps.component.scss` | layout |
| `.breadcrumb` | `src/app/layout/breadcrumb.component.scss` | layout |
| `footer` | `src/app/layout/site-footer.component.scss` | layout |
| `.panel`, `.panel h2`, `.panel-body`, `.panel-body > p:first-child`, `.intro`, `p`, `.hint`, `.fine-print`, `.instructions`, `.instructions li`, `.section-link` | `src/styles/_panel.scss` | partiel composant |
| `.table-scroll`, `.data-table` (+ `caption`, `th`, `td`, `thead th`, `tbody tr:nth-child(even)`, `tbody th`, `.numeric`), `.catalogue`, `.details th`, `.financial` (+ `.total`), `.rate` (+ media 760/520) | `src/styles/_data-table.scss` | partiel composant |
| `.form-row` (+ `label`), `input[type=text]`, `input[type=checkbox]`, `input[aria-invalid=true]`, `.required`, `.required-legend`, `.field-error` (+ `:empty`), `.actions`, `.actions a:not(.button)`, `button`/`.button` (+ `:hover`, `:active`, `:visited`), `.inline-actions`, `.acceptance` (+ `input`, `label`) (+ media 760/520) | `src/styles/_forms.scss` | partiel composant |
| `.message`, `.message p:last-child`, `.notice`, `.error`, `.error ul`, `.success` | `src/styles/_message.scss` | partiel composant |

Les partiels sont importes via `@use` par les composants qui en ont besoin
(`catalogue-list`, `product-detail`, `simulation-result`, `error-summary`, et les quatre
etapes du wizard), ce qui conserve l'encapsulation Angular tout en respectant le budget
`anyComponentStyle` de 8 kB.

### Harnais de non-regression visuelle

`e2e/visual/` contient un spec Playwright de capture par route (`/catalogue`,
`/produit/:code`, les quatre etapes du parcours, `/erreur/404`). Il est hors du gate par
defaut : `npm run visual`.

## Protection anti-CSRF (double-submit cookie)

Le jeton de session genere et compare par `SecurityFilter.java`, ainsi que le champ cache de
`fragments/csrf.jspf`, sont remplaces par le double-submit cookie de
`contracts/security-policy.json`. Aucun intercepteur CSRF maison n'existe : l'intercepteur XSRF
integre a `provideHttpClient` implemente exactement ce schema.

| | |
| --- | --- |
| Cookie | `XSRF-TOKEN` (emis par la passerelle) |
| Entete | `X-CSRF-Token` |
| Emission | `GET /api/v1/csrf-token` (`CsrfApiService.issueToken`), appele au bootstrap par le `provideAppInitializer` de `src/app/app.config.ts` avant le premier rendu |
| Entropie | 256 bits |
| Methodes protegees | POST, PUT, PATCH, DELETE |
| Rejet | 403 `application/problem+json` |
| Declaration | `XSRF_CONFIG` dans `src/app/app.config.ts`, consomme par `withXsrfConfiguration` dans `src/app/core/http.providers.ts` |

### Attributs du cookie (a verifier au deploiement)

Le cookie CSRF doit etre **lisible par le script** : `Path=/; Secure; SameSite=Strict` et
**sans** `HttpOnly`, sinon le SPA ne peut pas le reemettre en entete. Tout autre cookie reste
`HttpOnly`. `SameSite=Strict` est acceptable car le SPA et la passerelle partagent une origine
derriere l'ingress (OD-004). `deploy/smoke-headers.sh` echoue si le `Set-Cookie` de
`/api/v1/csrf-token` ne porte pas `SameSite=Strict` et `Secure`, ou s'il porte `HttpOnly`.

### Garde-fous et criteres mesurables

- `npm run check:csrf` (`scripts/check-csrf-names.mjs`, inclus dans `lint`) echoue si les noms de
  cookie et d'entete divergent du contrat, si un intercepteur CSRF maison reapparait, ou si
  `XSRF-TOKEN` / `X-CSRF-Token` apparait dans un appel `console.*` ou une interpolation de
  template. Le jeton n'est jamais journalise ni place dans une URL.
- 100 % des appels mutants portent un jeton valide : l'intercepteur integre ajoute l'entete a
  toute requete non sure des que le cookie est present
  (`src/app/core/http/xsrf.spec.ts`).
- Une mutation sans jeton est rejetee en 403 : scenario Gherkin
  « Soumission refusee en l'absence de jeton anti-CSRF » (`e2e/steps/securite-web.steps.ts`). Le
  rejet lui-meme (jeton absent, errone ou de plus de 100 caracteres) est applique par
  souscription-gateway ; le portail comportemental du SPA soumet l'etape souscripteur sans entete
  et verifie l'atterrissage sur `/erreur/403`, la regle etant celle de la doublure de passerelle
  (`e2e/support/fake-gateway.ts`).
- Les ressources statiques ne subissent aucune verification de jeton : `deploy/nginx.conf` ne
  contient aucun controle CSRF (scenario « Les ressources statiques ne requierent pas de jeton
  anti-CSRF »).
- Le taux de rejet 403 est surveille cote passerelle (metrique d'ingress), pas dans le SPA.
