import { Injectable, inject } from '@angular/core';
import { PreloadingStrategy, Route, Router } from '@angular/router';
import { Observable, from, switchMap } from 'rxjs';

/**
 * W9-T02 (docs/BENCHMARK.md): preloads every lazy route without competing with the landing LCP.
 *
 * Without preloading, each wizard transition waited for a chunk waterfall on top of its gateway
 * call (p95 up to 927 ms on the mid-tier profile, budget 500 ms). Preloading as soon as the landing
 * navigation ended, however, started the chunk downloads before the `/catalogue` LCP paint (2.73 s,
 * budget 2.5 s). The router runs the strategy after every successful navigation: after the landing
 * one, the downloads wait until the landed route has painted and the main thread is idle; after any
 * later navigation they start at once.
 */
@Injectable({ providedIn: 'root' })
export class PreloadAfterLandingStrategy implements PreloadingStrategy {
  private readonly router = inject(Router);

  preload(_route: Route, load: () => Observable<unknown>): Observable<unknown> {
    if (this.router.lastSuccessfulNavigation()?.previousNavigation) {
      return load();
    }
    return from(afterPaintAndIdle()).pipe(switchMap(load));
  }
}

/** Resolves once the next frame has been painted and the main thread has an idle period. */
function afterPaintAndIdle(): Promise<void> {
  return new Promise((resolve) => {
    requestAnimationFrame(() =>
      requestAnimationFrame(() => requestIdleCallback(() => resolve(), { timeout: 1000 })),
    );
  });
}
