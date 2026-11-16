import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { DraftStore } from './draft.store';
import { clearDraftRef, readRawDraftRef } from './draft-storage';
import { DRAFT_WINDOW_MS } from './draft-window';
import type { ProductDetail } from '../api/models';
import {
  requireConfirmation,
  requireDraft,
  requireSimulation,
  requireSubscriber,
} from './wizard.guards';

const PRODUCT: ProductDetail = {
  code: 'PRUDENT',
  name: 'Horizon Prudent',
  profile: 'Prudent',
  description: 'Support securise.',
  annualRate: '0.020000',
};

const route = {} as ActivatedRouteSnapshot;
const state = {} as RouterStateSnapshot;

/**
 * The three cascade routes and the guard each one declares in `wizard.routes.ts`. The
 * confirmation route is not part of the cascade: the source handled `/confirmation` before
 * reading the draft, so it is covered on its own below.
 */
const guardOf: Record<string, CanActivateFn> = {
  souscripteur: requireDraft,
  simulation: requireSubscriber,
  recapitulatif: requireSimulation,
};

const configure = (store: Partial<DraftStore>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      {
        provide: DraftStore,
        useValue: {
          hasDraft: signal(false),
          hasSubscriber: signal(false),
          hasSimulation: signal(false),
          completedReference: signal(null),
          expireIfStale: () => false,
          ...store,
        },
      },
    ],
  });
};

const run = (guard: CanActivateFn) => TestBed.runInInjectionContext(() => guard(route, state));

const serialize = (result: unknown) => TestBed.inject(Router).serializeUrl(result as UrlTree);

const expectRedirect = (guard: CanActivateFn, url: string) => {
  const result = run(guard);
  expect(result).toBeInstanceOf(UrlTree);
  expect(serialize(result)).toBe(url);
};

const noDraft = {};
const draftOnly = { hasDraft: signal(true) };
const withSubscriber = { hasDraft: signal(true), hasSubscriber: signal(true) };
const withSimulation = {
  hasDraft: signal(true),
  hasSubscriber: signal(true),
  hasSimulation: signal(true),
};

describe('wizard guards', () => {
  describe('cascade level 1: no draft', () => {
    for (const path of Object.keys(guardOf)) {
      it(`redirects /souscription/${path} to /catalogue?notice=parcours`, () => {
        configure(noDraft);
        expectRedirect(guardOf[path], '/catalogue?notice=parcours');
      });
    }
  });

  describe('cascade level 2: draft open, no subscriber', () => {
    it('lets /souscription/souscripteur through', () => {
      configure(draftOnly);
      expect(run(guardOf['souscripteur'])).toBe(true);
    });

    for (const path of ['simulation', 'recapitulatif']) {
      it(`redirects /souscription/${path} to /souscription/souscripteur`, () => {
        configure(draftOnly);
        expectRedirect(guardOf[path], '/souscription/souscripteur');
      });
    }
  });

  describe('cascade level 3: subscriber known, no simulation', () => {
    for (const path of ['souscripteur', 'simulation']) {
      it(`lets /souscription/${path} through`, () => {
        configure(withSubscriber);
        expect(run(guardOf[path])).toBe(true);
      });
    }

    for (const path of ['recapitulatif']) {
      it(`redirects /souscription/${path} to /souscription/simulation`, () => {
        configure(withSubscriber);
        expectRedirect(guardOf[path], '/souscription/simulation');
      });
    }
  });

  describe('cascade level 4: simulation attached', () => {
    for (const path of Object.keys(guardOf)) {
      it(`lets /souscription/${path} through`, () => {
        configure(withSimulation);
        expect(run(guardOf[path])).toBe(true);
      });
    }
  });

  describe('confirmation step: completed subscription only', () => {
    it('lets /souscription/confirmation through once a subscription is confirmed', () => {
      configure({ completedReference: signal('SUB-2024-000123') });
      expect(run(requireConfirmation)).toBe(true);
    });

    it('redirects /souscription/confirmation to /catalogue?notice=parcours without one', () => {
      configure({ completedReference: signal(null), ...withSimulation });
      expectRedirect(requireConfirmation, '/catalogue?notice=parcours');
    });
  });

  describe('expiry: draft past the window (former 20-minute session timeout)', () => {
    afterEach(() => vi.useRealTimers());

    const configureRealStore = () => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({ providers: [provideRouter([])] });
      clearDraftRef();
      vi.useFakeTimers();
      vi.setSystemTime(1_000_000);
      const store = TestBed.inject(DraftStore);
      store.start(PRODUCT, 'draft-1', 'key-1');
      return store;
    };

    for (const path of Object.keys(guardOf)) {
      it(`redirects /souscription/${path} to /catalogue?notice=parcours and clears the stored reference`, () => {
        configureRealStore();
        vi.setSystemTime(1_000_000 + DRAFT_WINDOW_MS);

        expectRedirect(guardOf[path], '/catalogue?notice=parcours');
        expect(readRawDraftRef()).toBeNull();
      });
    }

    it('lets the journey through just inside the window', () => {
      configureRealStore();
      vi.setSystemTime(1_000_000 + DRAFT_WINDOW_MS - 1);

      expect(run(requireDraft)).toBe(true);
      expect(readRawDraftRef()).not.toBeNull();
    });
  });
});
