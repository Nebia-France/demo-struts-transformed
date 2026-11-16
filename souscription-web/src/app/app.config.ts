import { APP_BASE_HREF } from '@angular/common';
import {
  HttpInterceptorFn,
  provideHttpClient,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import {
  ApplicationConfig,
  EnvironmentInjector,
  ErrorHandler,
  createEnvironmentInjector,
  inject,
  provideAppInitializer,
  provideZonelessChangeDetection,
  runInInjectionContext,
} from '@angular/core';
import { TitleStrategy, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { isAppError } from './core/error/app-error';
import { GlobalErrorHandler } from './core/error/global-error.handler';
import { DataTitleStrategy } from './core/data-title.strategy';
import { localeProviders } from './core/locale/locale.providers';
import { errorInterceptor } from './core/http/error.interceptor';
import { correlationInterceptor } from './core/http/correlation.interceptor';
import { TelemetryService } from './core/telemetry/telemetry.service';
import { timeoutInterceptor } from './core/http/timeout.interceptor';
import { DraftStore } from './wizard/draft.store';
import { clearDraftRef, readDraftRef } from './wizard/draft-storage';
import { EXPIRY_REDIRECT_URL, isExpired } from './wizard/draft-window';

/**
 * Double-submit cookie configuration of contracts/security-policy.json: the gateway issues the
 * `XSRF-TOKEN` cookie on `GET /api/v1/csrf-token` and expects it echoed in the `X-CSRF-Token`
 * header on POST/PUT/PATCH/DELETE (403 otherwise). Angular's built-in XSRF interceptor of
 * `provideHttpClient` implements exactly this scheme, so no custom CSRF interceptor exists.
 * The names declared here are fed to `withXsrfConfiguration(XSRF_CONFIG)` in
 * `core/http.providers.ts`, where `provideHttpClient` is declared per feature route so the http
 * layer stays out of the initial bundle. `scripts/check-csrf-names.mjs` fails the build if these
 * names drift from the contract or if the `withXsrfConfiguration` wiring disappears.
 */
export const XSRF_CONFIG = { cookieName: 'XSRF-TOKEN', headerName: 'X-CSRF-Token' } as const;

/**
 * The single declaration of the functional interceptor chain, in the order
 * correlation -> timeout -> error, applied after the built-in XSRF handling.
 * `core/http.providers.ts` feeds it to `withInterceptors(...)` in every `provideHttpClient`
 * call, so the order cannot drift between routes. `correlationInterceptor` comes first (W7-T05)
 * so every outgoing request leaves the browser with a `traceparent` header, including the ones
 * the timeout or the error interceptor ends up failing.
 */
export const httpInterceptorChain: HttpInterceptorFn[] = [
  correlationInterceptor,
  timeoutInterceptor,
  errorInterceptor,
];

// HttpClient is provided per feature route (see core/http.providers.ts -> httpProviders) so the
// http layer and its interceptor chain stay out of the routes that never call the gateway.
// `provideRouter` is declared in `main.ts`, not here: the eager `/catalogue` route needs
// `httpProviders`, which reads the two constants above, so importing `app.routes` from this file
// would close an initialisation cycle (app.config -> app.routes -> http.providers -> app.config).
export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    // index.html carries no `<base href>`: the served CSP sets `base-uri 'none'` (the
    // SecurityFilter.java baseline), which makes the browser ignore any `<base>` element. The
    // router base is declared here instead and the build emits root-absolute resource URLs
    // (`deployUrl: "/"`), so deep links such as /produit/:code load their bundles.
    { provide: APP_BASE_HREF, useValue: '/' },
    // The API services are `providedIn: 'root'`, so the `HttpClient` they inject is resolved in
    // the root injector, never in the per-route one. Without this declaration they would run on
    // the default client: no `traceparent` (W7-T05), the default `X-XSRF-TOKEN` header instead of
    // the `X-CSRF-Token` of contracts/security-policy.json, no 10 s timeout and, above all, no
    // `errorInterceptor`, so a purged draft (404/410) or a 503 would never be classified and the
    // journey would stall in place. The same chain and the same XSRF names as
    // `core/http.providers.ts`, which keeps providing them for the feature routes.
    provideHttpClient(withInterceptors(httpInterceptorChain), withXsrfConfiguration(XSRF_CONFIG)),
    ...localeProviders,
    // Document title read from the route `data.title` (replaces the `pageTitle` attribute).
    { provide: TitleStrategy, useExisting: DataTitleStrategy },
    // W5-T04: no screen is ever left blank by an uncaught error; it is reported and routed to
    // `/erreur/:status`, like the `<error-page>` forwards of web.xml did.
    { provide: ErrorHandler, useClass: GlobalErrorHandler },
    // Rehydrates the server-side draft after a reload, replacing the HTTP session that used to
    // survive it. Nothing is loaded when no draft reference is stored. The CSRF cookie is
    // fetched first so the very first mutating call already carries a valid token.
    provideAppInitializer(bootstrapSecurityAndDraft),
    // W7-T04/T06: resolves the release and the collector settings, then starts the Web Vitals
    // and journey collection. Declared after the draft initializer so a telemetry collector
    // that does not answer cannot delay the rehydration of the user's journey.
    provideAppInitializer(bootstrapTelemetry),
  ],
};

/**
 * Telemetry bootstrap: the deployed release is read from `/version.json` and the switch and
 * collector URL from `/runtime-config.json`, so every error report that follows carries the
 * version, then the Web Vitals observers and the journey listeners are registered.
 */
async function bootstrapTelemetry(): Promise<void> {
  const telemetry = inject(TelemetryService);
  const router = inject(Router);
  // Loaded lazily: the collection code is not needed to report an error, so it stays out of the
  // initial bundle measured by `scripts/check-initial-budget.mjs`.
  const { loadTelemetryRuntime } = await import('./core/telemetry/config');
  const { startWebVitals } = await import('./core/telemetry/web-vitals');
  await loadTelemetryRuntime();
  startWebVitals(telemetry, router);
}

/**
 * Bootstrap sequence: obtain the double-submit cookie, then rehydrate the draft.
 * `GET /api/v1/csrf-token` sets the `XSRF-TOKEN` cookie that the built-in XSRF interceptor
 * echoes as `X-CSRF-Token`; without it the first POST would be rejected with a 403.
 * The token value itself is never read, stored nor logged by the SPA.
 */
async function bootstrapSecurityAndDraft(): Promise<void> {
  // The injection context is captured synchronously: it does not survive the awaits below.
  const root = inject(EnvironmentInjector);
  await issueCsrfToken(root);
  await runInInjectionContext(root, rehydrateDraft);
}

/** Fetches the CSRF cookie through a short-lived injector holding the http layer. */
async function issueCsrfToken(parent: EnvironmentInjector): Promise<void> {
  const { httpProviders } = await import('./core/http.providers');
  const { CsrfApiService } = await import('./api/csrf-api.service');
  const injector = createEnvironmentInjector(httpProviders, parent);
  try {
    await firstValueFrom(injector.get(CsrfApiService).issueToken());
  } finally {
    injector.destroy();
  }
}

/**
 * Bootstrap rehydration: a stored `draftId` is resolved against `getDraft`. A reference past the
 * draft window, or a 404/410 answer (the server purged the draft), is treated as **expiry**, not
 * as a technical error: the store and the stored reference are cleared and the user lands on
 * `/catalogue?notice=parcours`, exactly like the legacy session timeout did.
 * HttpClient lives in a child injector so the http layer stays out of the initial bundle.
 */
async function rehydrateDraft(): Promise<void> {
  const stored = readDraftRef();
  if (!stored) {
    return;
  }
  const store = inject(DraftStore);
  const router = inject(Router);
  const expire = async (): Promise<void> => {
    store.clear();
    clearDraftRef();
    await router.navigateByUrl(EXPIRY_REDIRECT_URL);
  };
  if (isExpired(stored.createdAt)) {
    await expire();
    return;
  }
  const parent = inject(EnvironmentInjector);
  const { httpProviders } = await import('./core/http.providers');
  const { SubscriptionApiService } = await import('./api/subscription-api.service');
  const injector = createEnvironmentInjector(httpProviders, parent);
  try {
    const view = await firstValueFrom(injector.get(SubscriptionApiService).getDraft(stored.draftId));
    store.apply(view, stored.createdAt);
  } catch (error: unknown) {
    if (isAppError(error) && error.kind === 'expired') {
      await expire();
    }
  } finally {
    injector.destroy();
  }
}
