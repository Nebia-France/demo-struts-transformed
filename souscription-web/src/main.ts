import { ErrorHandler } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter, withComponentInputBinding, withPreloading } from '@angular/router';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { PreloadAfterLandingStrategy } from './app/core/preload-after-landing.strategy';
import { routes } from './app/app.routes';

// CONTRACT STUB SWITCH: set useGatewayStub=false once souscription-gateway /api/v1 is deployed.
// The flag is substituted at build time by the `define` option of angular.json ('true' in the
// development configuration, 'false' in production), so the production bundle drops the branch
// and msw is never shipped.
if (import.meta.env.NG_APP_USE_GATEWAY_STUB === 'true') {
  const { worker } = await import('./app/api/testing/browser');
  await worker.start({ onUnhandledRequest: 'bypass' });
}

// W9-T02 (docs/BENCHMARK.md): the lazy route chunks are fetched in the background from the first
// in-app navigation on, so a step transition no longer waits for a chunk waterfall on top of its
// gateway call, while the landing route LCP is not competed for (PreloadAfterLandingStrategy).
bootstrapApplication(App, {
  providers: [
    provideRouter(routes, withComponentInputBinding(), withPreloading(PreloadAfterLandingStrategy)),
    ...appConfig.providers,
  ],
})
  .then((app) => {
    // A rejected promise that nobody awaited never reaches Angular's ErrorHandler, so it would
    // leave the current screen untouched and the failure invisible. The hook routes it into the
    // same handler, which is the SPA equivalent of the `<error-page>` catch-all of web.xml.
    const handler = app.injector.get(ErrorHandler);
    window.addEventListener('unhandledrejection', (event: PromiseRejectionEvent) => {
      event.preventDefault();
      handler.handleError(event.reason);
    });
  })
  .catch((err) => console.error(err));
