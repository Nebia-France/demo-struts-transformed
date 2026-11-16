import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CatalogueApiService } from '../api/catalogue-api.service';
import type { ProductDetail, ProductSummary } from '../api/models';

/**
 * Typed replacement for the catalogue half of the legacy `CatalogAction`, which reached
 * its collaborator through `Application.get(getServlet().getServletContext())`.
 * The collaborator is now injected by class token: the wiring is checked at compile time
 * and there is no name-based lookup left.
 *
 * Stateless: it holds no field other than the injected service.
 */
@Injectable({ providedIn: 'root' })
export class CatalogueFacade {
  private readonly api = inject(CatalogueApiService);

  /**
   * Without a code, the whole catalogue (former the legacy `catalogue` action); with a code, the
   * product sheet (former the `catalogue` action carrying a code parameter).
   */
  products(): Observable<ProductSummary[]>;
  products(code: string): Observable<ProductDetail>;
  products(code?: string): Observable<ProductSummary[] | ProductDetail> {
    return code === undefined ? this.api.listProducts() : this.api.getProduct(code);
  }
}
