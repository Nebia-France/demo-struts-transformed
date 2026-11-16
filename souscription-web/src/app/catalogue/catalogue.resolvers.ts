import { inject } from '@angular/core';
import { ResolveFn, Router } from '@angular/router';
import { EMPTY, Observable, catchError } from 'rxjs';
import { CatalogueApiService } from '../api/catalogue-api.service';
import type { ProductDetail, ProductSummary } from '../api/models';
import { errorPageUrl, isAppError } from '../core/error/app-error';

/**
 * Functional route resolvers replacing the server rendering of the `catalogue` view and
 * `produit` view: the gateway request is issued by the router while the SPA bootstraps,
 * instead of after a server round trip.
 *
 * A failed load lands on `/erreur/:status`, which is what the `<error-page>` forwards of
 * `web.xml` did for an unknown product code (404), a disallowed method (405) or a server
 * failure: the navigation is cancelled so no half-rendered screen is shown. The classification
 * comes from `errorInterceptor`; a transport failure (`network`) has no status of its own and
 * uses the generic message of the error page.
 */
export const catalogueResolver: ResolveFn<ProductSummary[]> = () =>
  inject(CatalogueApiService).listProducts().pipe(toErrorPage());

export const productResolver: ResolveFn<ProductDetail> = (route) =>
  inject(CatalogueApiService).getProduct(route.paramMap.get('code')!).pipe(toErrorPage());

/** Routes a failed load to the error page and cancels the navigation in progress. */
function toErrorPage<T>() {
  const router = inject(Router);
  return (source: Observable<T>): Observable<T> =>
    source.pipe(
      catchError((error: unknown) => {
        void router.navigateByUrl(errorPageUrl(loadStatus(error)));
        return EMPTY;
      }),
    );
}

/**
 * Status rendered by the error page: the gateway status for the `http` family, 404 when the
 * resource is gone (unknown or mal-formed product code) and 503 for a transport failure.
 */
function loadStatus(error: unknown): number {
  if (!isAppError(error)) {
    return 500;
  }
  switch (error.kind) {
    case 'http':
      return error.status;
    case 'network':
      return 503;
    default:
      return 404;
  }
}
