import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { DraftStore } from './draft.store';

/**
 * Functional replacement for the sequencing cascade of the legacy `SubscriptionAction`:
 * `draft == null -> restart()`, `subscriber == null -> subscriber step`,
 * `simulation == null -> simulation step`.
 *
 * A draft past the 20-minute window (former `<session-timeout>20</session-timeout>`) counts as
 * absent: the store is expired first, which drops the stored draft reference so a reload cannot
 * resurrect the journey, then the cascade redirects to `/catalogue?notice=parcours`.
 */
const restart = (router: Router): UrlTree =>
  router.createUrlTree(['/catalogue'], { queryParams: { notice: 'parcours' } });

/** Expires a stale journey before reading the cascade, so expiry behaves like "no draft". */
const draftOpen = (draft: DraftStore): boolean => {
  draft.expireIfStale();
  return draft.hasDraft();
};

export const requireDraft: CanActivateFn = () => {
  const router = inject(Router);
  return draftOpen(inject(DraftStore)) ? true : restart(router);
};

export const requireSubscriber: CanActivateFn = () => {
  const router = inject(Router);
  const draft = inject(DraftStore);
  if (!draftOpen(draft)) {
    return restart(router);
  }
  return draft.hasSubscriber() ? true : router.createUrlTree(['/souscription/souscripteur']);
};

export const requireSimulation: CanActivateFn = () => {
  const router = inject(Router);
  const draft = inject(DraftStore);
  if (!draftOpen(draft)) {
    return restart(router);
  }
  if (!draft.hasSubscriber()) {
    return router.createUrlTree(['/souscription/souscripteur']);
  }
  return draft.hasSimulation() ? true : router.createUrlTree(['/souscription/simulation']);
};

/**
 * Guard of the confirmation step. The source handled `/confirmation` before the draft cascade:
 * `completed == null -> restart()`, otherwise the stored subscription was displayed. The draft
 * itself is gone at that point (`session.removeAttribute("draft")`), so the cascade must not
 * apply here.
 */
export const requireConfirmation: CanActivateFn = () => {
  const router = inject(Router);
  return inject(DraftStore).completedReference() !== null ? true : restart(router);
};
