import { MonoTypeOperatorFunction, Observable, Subject } from 'rxjs';

/**
 * Bulkhead for outgoing gateway calls: at most `BULKHEAD_LIMIT` requests are in flight
 * at any time (contracts/resilience-contract.json -> bulkhead.maxConcurrentCalls = 6).
 * Calls beyond the limit wait for a slot instead of being rejected; the 10 s timeout
 * starts only once the slot is acquired, because the operator defers the subscription
 * to its source.
 */
export const BULKHEAD_LIMIT = 6;

export function withBulkhead<T>(limit = BULKHEAD_LIMIT): MonoTypeOperatorFunction<T> {
  let inFlight = 0;
  const waiting: (() => void)[] = [];

  const release = () => {
    inFlight--;
    waiting.shift()?.();
  };

  return (source: Observable<T>) =>
    new Observable<T>((subscriber) => {
      let released = false;
      const releaseOnce = () => {
        if (!released) {
          released = true;
          release();
        }
      };

      const cancelled = new Subject<void>();
      const start = () => {
        if (subscriber.closed) {
          releaseOnce();
          return;
        }
        const inner = source.subscribe({
          next: (value) => subscriber.next(value),
          error: (err) => {
            releaseOnce();
            subscriber.error(err);
          },
          complete: () => {
            releaseOnce();
            subscriber.complete();
          },
        });
        cancelled.subscribe(() => inner.unsubscribe());
      };

      let queued: (() => void) | undefined;
      if (inFlight < limit) {
        inFlight++;
        start();
      } else {
        queued = () => {
          inFlight++;
          start();
        };
        waiting.push(queued);
      }

      return () => {
        if (queued) {
          const index = waiting.indexOf(queued);
          if (index >= 0) {
            waiting.splice(index, 1);
            return;
          }
        }
        cancelled.next();
        releaseOnce();
      };
    });
}
