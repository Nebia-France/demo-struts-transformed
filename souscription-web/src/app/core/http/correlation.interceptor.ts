import { HttpInterceptorFn } from '@angular/common/http';
import { newTraceparent } from './traceparent';

/**
 * W7-T05: first link of the functional chain (correlation -> timeout -> error, which runs after
 * the built-in anti-forgery handling provided by `provideHttpClient`).
 * Every outgoing request carries a `traceparent` header, so 100% of the API calls
 * are joinable with the gateway trace of the same call, and a client error reported afterwards
 * carries the trace id of the call that caused it.
 *
 * It is declared first on purpose: a request rejected by the timeout or mapped by the error
 * interceptor must already carry its header when it leaves the browser.
 */
export const correlationInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.clone({ setHeaders: { traceparent: newTraceparent() } }));
