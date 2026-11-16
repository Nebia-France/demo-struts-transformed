import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base-url';
import type { operations } from './models/schema';

/** Body of the `issueCsrfToken` operation. */
export type CsrfToken =
  operations['issueCsrfToken']['responses']['200']['content']['application/json'];

/**
 * CSRF token issuance (`issueCsrfToken`). The gateway also sets the `XSRF-TOKEN` cookie that
 * `withXsrfConfiguration` echoes as `X-CSRF-Token`; this call is made at bootstrap (plan 31).
 */
@Injectable({ providedIn: 'root' })
export class CsrfApiService {
  private readonly http = inject(HttpClient);

  issueToken(): Observable<CsrfToken> {
    return this.http.get<CsrfToken>(`${API_BASE_URL}/csrf-token`);
  }
}
