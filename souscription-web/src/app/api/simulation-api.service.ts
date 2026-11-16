import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base-url';
import type { SimulationResult } from './models';
import type { operations } from './models/schema';

/** Request body of the `simulate` operation: the amounts plus the product code. */
export type SimulationRequest = operations['simulate']['requestBody']['content']['application/json'];

/**
 * Compound-interest projection (`simulate`). The annual rate is never supplied by the
 * client: the gateway resolves it from the catalogue.
 */
@Injectable({ providedIn: 'root' })
export class SimulationApiService {
  private readonly http = inject(HttpClient);

  simulate(input: SimulationRequest): Observable<SimulationResult> {
    return this.http.post<SimulationResult>(`${API_BASE_URL}/simulations`, input);
  }
}
