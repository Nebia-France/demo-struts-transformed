import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import type { ConfirmationRequest, DraftView, SubscriptionConfirmation } from '../api/models';
import { SubscriptionApiService } from '../api/subscription-api.service';
import type { AppError } from '../core/error/app-error';
import { ERROR_MESSAGES } from '../core/error/error-catalog';
import { localeProviders } from '../core/locale/locale.providers';
import { DraftStore } from './draft.store';
import { clearDraftRef } from './draft-storage';
import { SummaryStepComponent } from './summary-step.component';
import { expectNoA11yViolations } from '../testing/axe';

const normalise = (value: string | null | undefined): string =>
  (value ?? '').replace(/[\u00A0\u202F]/g, ' ').trim();

/** Lets the confirmation promise chain settle before the DOM is asserted. */
const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

const VIEW: DraftView = {
  draftId: '7f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d',
  product: {
    code: 'EQUILIBRE',
    name: 'Horizon Equilibre',
    profile: 'Equilibre',
    description: 'Support equilibre.',
    annualRate: '0.040000',
  },
  subscriber: {
    firstName: 'Camille',
    lastName: 'Durand',
    email: 'camille.durand@example.com',
    birthDate: '1985-03-14',
  },
  simulation: {
    initialAmount: '2500.00',
    monthlyAmount: '250.00',
    durationYears: 12,
    contributed: '38500.00',
    finalCapital: '42000.00',
  },
  confirmationKey: '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed',
  status: 'SIMULATED',
};

class SubscriptionApiStub {
  readonly confirmed: ConfirmationRequest[] = [];
  /** Left open so the in-flight guard can be observed; completed on demand. */
  readonly pending = new Subject<SubscriptionConfirmation>();
  hold = false;
  /** Failure the gateway answers with, already classified by `errorInterceptor`. */
  fail: AppError | null = null;

  confirmDraft(
    draftId: string,
    request: ConfirmationRequest,
  ): Observable<SubscriptionConfirmation> {
    this.confirmed.push(request);
    if (this.fail !== null) {
      return throwError(() => this.fail);
    }
    return this.hold
      ? this.pending.asObservable()
      : of({
          reference: 'SUB-2024-000123',
          product: { code: 'EQUILIBRE', name: 'Horizon Equilibre', profile: 'Equilibre', annualRate: '0.040000' },
          subscriber: VIEW.subscriber!,
          simulation: VIEW.simulation!,
          createdAt: '2024-05-01T09:30:00Z',
        });
  }
}

describe('SummaryStepComponent (recapitulatif view)', () => {
  let api: SubscriptionApiStub;

  const render = () => {
    const fixture = TestBed.createComponent(SummaryStepComponent);
    fixture.detectChanges();
    return fixture;
  };

  beforeEach(() => {
    clearDraftRef();
    api = new SubscriptionApiStub();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        ...localeProviders,
        // The confirmation target must exist: `ConfirmService` navigates there on success.
        provideRouter([{ path: 'souscription/confirmation', children: [] }]),
        { provide: SubscriptionApiService, useValue: api },
      ],
    });
    TestBed.inject(DraftStore).apply(VIEW);
  });

  it('recaps the product and the subscriber of the draft', () => {
    const cells = Array.from(
      (render().nativeElement as HTMLElement).querySelectorAll('table.details td'),
    ).map((cell) => normalise(cell.textContent));

    expect(cells).toEqual([
      'Horizon Equilibre — Equilibre',
      'Camille Durand',
      'camille.durand@example.com',
      '14/03/1985',
    ]);
  });

  it('embeds the shared result block with #capital-final', () => {
    const element = render().nativeElement as HTMLElement;

    expect(element.querySelector('app-simulation-result')).not.toBeNull();
    expect(normalise(element.querySelector('#capital-final')?.textContent)).toBe('42 000,00 €');
  });

  it('refuses the confirmation while the acceptance box is unticked', () => {
    const fixture = render();
    const element = fixture.nativeElement as HTMLElement;

    element.querySelector('form')?.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    expect(api.confirmed).toEqual([]);
    expect(normalise(element.querySelector('#accepted-error')?.textContent)).toBe(
      'Vous devez valider les informations et les hypothèses de simulation.',
    );
    expect(element.querySelector('#accepted')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('confirms with the key of the draft once the acceptance box is ticked', () => {
    const fixture = render();
    const element = fixture.nativeElement as HTMLElement;
    const box = element.querySelector('#accepted') as HTMLInputElement;
    box.click();
    fixture.detectChanges();

    element.querySelector('form')?.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    expect(api.confirmed).toEqual([{ confirmationKey: VIEW.confirmationKey }]);
  });

  it('disables the submit control while the confirmation is in flight', async () => {
    api.hold = true;
    const fixture = render();
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('#accepted') as HTMLInputElement).click();
    fixture.detectChanges();

    element.querySelector('form')?.dispatchEvent(new Event('submit'));
    await Promise.resolve();
    fixture.detectChanges();

    expect((element.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(
      true,
    );
  });

  /**
   * W5-T05: the source caught `SQLException` and answered 503 by redisplaying the recap with
   * the message in page and the input intact. The SPA must do the same: no error route, no
   * blank screen.
   */
  it('shows the 503 message in page, keeps the input and offers a retry', async () => {
    api.fail = { kind: 'http', status: 503 };
    const fixture = render();
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('#accepted') as HTMLInputElement).click();
    fixture.detectChanges();

    element.querySelector('form')?.dispatchEvent(new Event('submit'));
    await flush();
    fixture.detectChanges();

    expect(normalise(element.querySelector('#storage-error')?.textContent)).toBe(
      ERROR_MESSAGES['503'],
    );
    // Input preserved and retryable: the acceptance box is still ticked and the control is live.
    expect((element.querySelector('#accepted') as HTMLInputElement).checked).toBe(true);
    const submit = element.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    expect(normalise(submit.textContent)).toBe('Réessayer');
    // The recap itself is still rendered, so nothing navigated away.
    expect(element.querySelector('table.details')).not.toBeNull();
  });

  it('shows the retryable message of a network failure in page', async () => {
    api.fail = { kind: 'network' };
    const fixture = render();
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('#accepted') as HTMLInputElement).click();
    fixture.detectChanges();

    element.querySelector('form')?.dispatchEvent(new Event('submit'));
    await flush();
    fixture.detectChanges();

    expect(normalise(element.querySelector('#storage-error')?.textContent)).toBe(
      ERROR_MESSAGES['network'],
    );
    expect((element.querySelector('#accepted') as HTMLInputElement).checked).toBe(true);
  });
  it('renders the summary step without serious or critical accessibility violation', async () => {
    const host = render().nativeElement as HTMLElement;

    await expectNoA11yViolations(host);
  });
});
