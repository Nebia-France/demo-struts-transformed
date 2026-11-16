import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ErrorPageComponent } from './error-page.component';
import { ERROR_MESSAGES } from './error-catalog';
import { ERROR_LABELS } from './labels';

/**
 * W5-T01: one case per `<error-page>` mapping of `web.xml` (400, 403, 404, 405, 409, 500), plus
 * 503 and an unknown status, both falling back to the default wording of the `erreur` view.
 */
describe('ErrorPageComponent (erreur view port)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    });
  });

  async function render(code: string): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(ErrorPageComponent);
    fixture.componentRef.setInput('code', code);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const CASES: [string, string][] = [
    ['400', ERROR_MESSAGES['400']],
    ['403', ERROR_MESSAGES['403']],
    ['404', ERROR_MESSAGES['404']],
    ['405', ERROR_MESSAGES['405']],
    ['409', ERROR_MESSAGES['409']],
    // `web.xml` mapped 500 and `java.lang.Throwable` to the same page: generic wording.
    ['500', ERROR_MESSAGES['default']],
    ['503', ERROR_MESSAGES['503']],
    ['999', ERROR_MESSAGES['default']],
  ];

  for (const [code, message] of CASES) {
    it(`renders the catalogue message for the status ${code}`, async () => {
      const host = await render(code);
      const alert = host.querySelector('[role="alert"]');

      expect(alert).not.toBeNull();
      expect(alert?.textContent).toContain(message);
      expect(host.textContent).toContain(code);
    });
  }

  it('never renders a raw response payload', async () => {
    const host = await render('500');
    const text = host.textContent ?? '';

    expect(text).not.toContain('detail');
    expect(text).not.toContain('stack');
    expect(text).not.toContain('{');
    expect(text).not.toContain('Exception');
  });

  it('offers the return-to-catalogue link of the source page', async () => {
    const link = (await render('404')).querySelector('a.button');

    expect(link?.getAttribute('href')).toBe('/catalogue');
    expect(link?.textContent?.trim()).toBe(ERROR_LABELS.backToCatalogue);
  });
});
