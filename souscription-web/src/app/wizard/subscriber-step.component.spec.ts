import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import type { DraftView, FieldErrors, SubscriberInput } from '../api/models';
import { toAppError } from '../core/http/error.interceptor';
import { SubscriptionApiService } from '../api/subscription-api.service';
import { DraftStore } from './draft.store';
import { clearDraftRef } from './draft-storage';
import { SubscriberStepComponent } from './subscriber-step.component';
import { expectNoA11yViolations } from '../testing/axe';

const PRODUCT: DraftView['product'] = {
  code: 'PRUDENT',
  name: 'Horizon Prudent',
  profile: 'Prudent',
  description: 'Support prudent.',
  annualRate: '0.020000',
};

const VIEW: DraftView = {
  draftId: '7f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d',
  product: PRODUCT,
  subscriber: {
    firstName: 'Camille',
    lastName: 'Durand',
    email: 'camille.durand@example.com',
    birthDate: '1985-03-14',
  },
  simulation: null,
  confirmationKey: '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed',
  status: 'SUBSCRIBER_CAPTURED',
};

const REFUSAL: FieldErrors = {
  type: 'about:blank',
  title: 'Validation échouée',
  status: 400,
  errors: [
    {
      field: 'birthDate',
      message: 'La date de naissance doit respecter le format JJ/MM/AAAA.',
      code: 'birthDate.format',
    },
  ],
};

class SubscriptionApiStub {
  readonly saved: SubscriberInput[] = [];
  /** Server refusal of the next submission, as mapped by `errorInterceptor`. */
  refusal: FieldErrors | null = null;

  putDraftSubscriber(draftId: string, input: SubscriberInput): Observable<DraftView> {
    this.saved.push(input);
    if (this.refusal !== null) {
      const error = new HttpErrorResponse({ status: 400, error: this.refusal });
      return throwError(() => toAppError(error));
    }
    return of({ ...VIEW, subscriber: input });
  }
}

describe('SubscriberStepComponent', () => {
  let api: SubscriptionApiStub;

  beforeEach(() => {
    clearDraftRef();
    api = new SubscriptionApiStub();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: SubscriptionApiService, useValue: api },
      ],
    });
  });

  async function render(): Promise<{
    host: HTMLElement;
    component: SubscriberStepComponent;
  }> {
    const fixture = TestBed.createComponent(SubscriberStepComponent);
    await fixture.whenStable();
    return { host: fixture.nativeElement as HTMLElement, component: fixture.componentInstance };
  }

  function field(host: HTMLElement, id: string): HTMLInputElement {
    const input = host.querySelector<HTMLInputElement>(`input#${id}`);
    if (input === null) {
      throw new Error(`Champ ${id} absent du formulaire.`);
    }
    return input;
  }

  it('starts empty, as the legacy form did for the identity fields', async () => {
    TestBed.inject(DraftStore).start(PRODUCT, VIEW.draftId, VIEW.confirmationKey);

    const { host } = await render();

    for (const id of ['firstName', 'lastName', 'email', 'birthDate']) {
      expect(field(host, id).value).toBe('');
    }
  });

  it('re-entering step 2 repopulates the group from the stored draft, date in dd/MM/yyyy', async () => {
    TestBed.inject(DraftStore).apply(VIEW);

    const { host } = await render();

    expect(field(host, 'firstName').value).toBe('Camille');
    expect(field(host, 'lastName').value).toBe('Durand');
    expect(field(host, 'email').value).toBe('camille.durand@example.com');
    expect(field(host, 'birthDate').value).toBe('14/03/1985');
  });

  it('renders a null draft value as an empty string, never as null', async () => {
    TestBed.inject(DraftStore).apply({
      ...VIEW,
      subscriber: {
        firstName: null,
        lastName: null,
        email: null,
        birthDate: null,
      } as unknown as SubscriberInput,
    });

    const { host } = await render();

    for (const id of ['firstName', 'lastName', 'email', 'birthDate']) {
      expect(field(host, id).value).toBe('');
    }
  });

  it('keeps the maxlength, size and aria attributes of souscripteur view', async () => {
    TestBed.inject(DraftStore).apply(VIEW);

    const { host } = await render();

    expect(field(host, 'firstName').getAttribute('maxlength')).toBe('80');
    expect(field(host, 'lastName').getAttribute('maxlength')).toBe('80');
    expect(field(host, 'email').getAttribute('maxlength')).toBe('254');
    expect(field(host, 'birthDate').getAttribute('maxlength')).toBe('10');
    expect(field(host, 'birthDate').getAttribute('size')).toBe('12');
    expect(field(host, 'email').getAttribute('aria-describedby')).toBe('email-hint email-error');
    expect(field(host, 'birthDate').getAttribute('aria-describedby')).toBe(
      'birthDate-hint birthDate-error',
    );
    for (const id of ['firstName', 'lastName', 'email', 'birthDate']) {
      expect(field(host, id).hasAttribute('required')).toBe(true);
      expect(field(host, id).getAttribute('aria-invalid')).toBe('false');
    }
    expect(host.querySelector('#email-hint')?.textContent).toBe(
      'Exemple : prenom.nom@example.com',
    );
    expect(host.querySelector('#birthDate-hint')?.textContent).toBe(
      'JJ/MM/AAAA — réservé aux personnes de 18 ans et plus.',
    );
  });

  it('submit persists the subscriber with an ISO birth date, then navigates to the simulation', async () => {
    TestBed.inject(DraftStore).apply(VIEW);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const { component } = await render();
    component.submit();

    expect(api.saved).toEqual([
      {
        firstName: 'Camille',
        lastName: 'Durand',
        email: 'camille.durand@example.com',
        birthDate: '1985-03-14',
      },
    ]);
    expect(navigate).toHaveBeenCalledWith(['/souscription/simulation']);
  });

  it('an incomplete group is not submitted at all', async () => {
    TestBed.inject(DraftStore).start(PRODUCT, VIEW.draftId, VIEW.confirmationKey);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const { component } = await render();
    component.submit();

    expect(api.saved).toEqual([]);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('a client-side refusal states why under the field, worded as the server rule', async () => {
    TestBed.inject(DraftStore).start(PRODUCT, VIEW.draftId, VIEW.confirmationKey);
    const fixture = TestBed.createComponent(SubscriberStepComponent);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;

    field(host, 'birthDate').value = '1985-04-12';
    field(host, 'birthDate').dispatchEvent(new Event('input'));
    fixture.componentInstance.submit();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(field(host, 'firstName').getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#firstName-error')?.textContent).toContain('Indiquez votre prénom');
    expect(host.querySelector('#birthDate-error')?.textContent).toContain('format JJ/MM/AAAA');
    expect(api.saved).toEqual([]);
  });

  it('renders no hidden CSRF field: the token is carried by the interceptor', async () => {
    TestBed.inject(DraftStore).apply(VIEW);

    const { host } = await render();

    expect(host.querySelector('input[type="hidden"]')).toBeNull();
  });

  it('a 400 field refusal re-renders the step with the summary, aria-invalid and no navigation', async () => {
    TestBed.inject(DraftStore).apply(VIEW);
    api.refusal = REFUSAL;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    const fixture = TestBed.createComponent(SubscriberStepComponent);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    fixture.componentInstance.submit();
    await fixture.whenStable();

    const summary = host.querySelector('#erreurs');
    expect(summary?.getAttribute('role')).toBe('alert');
    expect(summary?.getAttribute('class')).toBe('message error');
    expect(summary?.querySelectorAll('li')[0].textContent).toBe(
      'La date de naissance doit respecter le format JJ/MM/AAAA.',
    );
    expect(field(host, 'birthDate').getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#birthDate-error')?.textContent).toBe(
      'La date de naissance doit respecter le format JJ/MM/AAAA.',
    );
    expect(navigate).not.toHaveBeenCalled();
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('editing the refused field clears its message and the summary entry', async () => {
    TestBed.inject(DraftStore).apply(VIEW);
    api.refusal = REFUSAL;

    const fixture = TestBed.createComponent(SubscriberStepComponent);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    fixture.componentInstance.submit();
    await fixture.whenStable();

    const input = field(host, 'birthDate');
    input.value = '13/04/1985';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(host.querySelector('#erreurs')).toBeNull();
    expect(host.querySelector('#birthDate-error')?.textContent).toBe('');
    expect(input.getAttribute('aria-invalid')).toBe('false');
  });
  it('renders the subscriber step without serious or critical accessibility violation', async () => {
    TestBed.inject(DraftStore).apply(VIEW);

    const { host } = await render();

    await expectNoA11yViolations(host);
  });
});
