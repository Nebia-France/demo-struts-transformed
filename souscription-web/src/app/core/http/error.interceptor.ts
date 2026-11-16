import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import type { FieldErrors } from '../../api/models';
import type { AppError } from '../error/app-error';
import { isExpiryStatus } from '../../wizard/draft-window';

/**
 * Last link of the chain (csrf -> timeout -> error): maps transport and gateway failures onto
 * the five families of `AppError`, so no consumer reads a status code again.
 *
 * | Failure                                   | Family                        | Behaviour                                                   |
 * | ----------------------------------------- | ----------------------------- | ----------------------------------------------------------- |
 * | status 0 (offline, aborted, CORS)         | `network`                     | retryable message in place, user input preserved            |
 * | 400 with an `errors` array                | `field`                       | reprojected onto the form, never navigates to an error page |
 * | 404, 410                                  | `expired`                     | clears the store, `/catalogue?notice=parcours`              |
 * | 409                                       | `conflict`                    | redisplays the existing confirmation (idempotent replay)    |
 * | any other status (400 without field, 403, 405, 500, 503...) | `http`       | `/erreur/:status`                                           |
 *
 * A failed call never clears the store; only the `expired` family does, deliberately.
 * The chain adds no retry (`npm run check:no-retry`): the error is mapped and rethrown as is.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    catchError((error: HttpErrorResponse) => throwError(() => toAppError(error))),
  );

/** Single classification point of the gateway's `application/problem+json` answers. */
export function toAppError(error: HttpErrorResponse): AppError {
  if (error.status === 0) {
    return { kind: 'network' };
  }
  if (error.status === 400 && hasFieldErrors(error)) {
    return { kind: 'field', fields: error.error as FieldErrors };
  }
  if (isExpiryStatus(error.status)) {
    return { kind: 'expired' };
  }
  if (error.status === 409) {
    return { kind: 'conflict' };
  }
  return { kind: 'http', status: error.status, detail: problem(error)?.detail };
}

/** A validation refusal carries at least one entry in the `errors` array of the contract. */
function hasFieldErrors(error: HttpErrorResponse): boolean {
  return (problem(error)?.errors?.length ?? 0) > 0;
}

function problem(error: HttpErrorResponse): FieldErrors | null {
  const body: unknown = error.error;
  return typeof body === 'object' && body !== null ? (body as FieldErrors) : null;
}
