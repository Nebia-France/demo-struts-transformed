import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { resetTelemetryConfig, setTelemetryConfig } from '../core/telemetry/config';
import { SiteFooterComponent, footerRelease } from './site-footer.component';

describe('SiteFooterComponent (diagnostic release, W9-T04)', () => {
  const SHA = '6fb6a84c121bb6ceb2e97c259d1b3e72852a1c76';

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  afterEach(() => resetTelemetryConfig());

  it('renders the release and build date read from /version.json', async () => {
    setTelemetryConfig({ release: SHA, builtAt: '2026-11-13T18:25:06.598Z' });
    const fixture = TestBed.createComponent(SiteFooterComponent);
    // The release is read through a lazy import of the telemetry configuration.
    await vi.waitFor(() => expect(fixture.componentInstance.version()).not.toBeNull(), { timeout: 5000 });
    await fixture.whenStable();

    const release = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="release"]');
    expect(release?.textContent?.trim()).toBe('Version 6fb6a84c121b du 2026-11-13');
    expect(release?.getAttribute('data-release')).toBe(SHA);
  });

  it('keeps the legacy footer texts', async () => {
    const fixture = TestBed.createComponent(SiteFooterComponent);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Financière Horizon — Épargne & placements');
    expect(text).toContain('Souscription en ligne');
  });

  it('shortens a commit sha and keeps any other release verbatim', () => {
    expect(footerRelease(SHA, '2026-11-13T00:00:00Z')).toEqual({
      release: SHA,
      short: '6fb6a84c121b',
      builtOn: '2026-11-13',
    });
    expect(footerRelease('unknown', 'unknown')).toEqual({
      release: 'unknown',
      short: 'unknown',
      builtOn: 'unknown',
    });
  });
});
