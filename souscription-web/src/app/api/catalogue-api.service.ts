import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base-url';
import type { ProductDetail, ProductSummary } from './models';

/**
 * Catalogue reads of the gateway contract (`listProducts`, `getProduct`).
 * Stateless by design: the signal store is the sole owner of business state.
 */
@Injectable({ providedIn: 'root' })
export class CatalogueApiService {
  private readonly http = inject(HttpClient);

  listProducts(): Observable<ProductSummary[]> {
    return this.http.get<ProductSummary[]>(`${API_BASE_URL}/products`);
  }

  getProduct(code: string): Observable<ProductDetail> {
    return this.http.get<ProductDetail>(`${API_BASE_URL}/products/${encodeURIComponent(code)}`);
  }
}
