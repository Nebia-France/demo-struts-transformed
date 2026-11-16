import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Navigation, Router } from '@angular/router';
import { of } from 'rxjs';
import { PreloadAfterLandingStrategy } from './preload-after-landing.strategy';

describe('PreloadAfterLandingStrategy', () => {
  const lastSuccessfulNavigation = signal<Navigation | null>(null);

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: Router, useValue: { lastSuccessfulNavigation } }],
    });
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => setTimeout(() => cb(0), 0));
    vi.stubGlobal('requestIdleCallback', (cb: () => void) => setTimeout(cb, 0));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('defers the landing preload until the route has painted and the thread is idle', async () => {
    lastSuccessfulNavigation.set({ previousNavigation: null } as Navigation);
    const load = vi.fn(() => of('chunk'));

    const done = new Promise((resolve) =>
      TestBed.inject(PreloadAfterLandingStrategy).preload({}, load).subscribe(resolve),
    );

    expect(load).not.toHaveBeenCalled();
    await expect(done).resolves.toBe('chunk');
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('preloads at once after any later navigation', () => {
    lastSuccessfulNavigation.set({ previousNavigation: {} as Navigation } as Navigation);
    const load = vi.fn(() => of('chunk'));

    let loaded: unknown;
    TestBed.inject(PreloadAfterLandingStrategy).preload({}, load).subscribe((v) => (loaded = v));

    expect(load).toHaveBeenCalledTimes(1);
    expect(loaded).toBe('chunk');
  });
});
