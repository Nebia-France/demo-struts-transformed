import { HttpEvent, HttpInterceptorFn } from '@angular/common/http';
import { timeout } from 'rxjs';
import { withBulkhead } from './concurrency';

/**
 * Resilience contract (contracts/resilience-contract.json):
 * timeout 10 s, 0 retry, no circuit breaker, at most 6 concurrent calls per origin.
 */
export const TIMEOUT_MS = 10_000;

const bulkhead = withBulkhead<HttpEvent<unknown>>();

export const timeoutInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(timeout({ each: TIMEOUT_MS }), bulkhead);
