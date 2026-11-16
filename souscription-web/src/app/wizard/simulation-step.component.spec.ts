import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Observable, of } from 'rxjs';
import type { DraftView, SimulationInput, SimulationResult } from '../api/models';
import type { SimulationRequest } from '../api/simulation-api.service';
import { SimulationApiService } from '../api/simulation-api.service';
import { SubscriptionApiService } from '../api/subscription-api.service';
import { localeProviders } from '../core/locale/locale.providers';
import { DraftStore } from './draft.store';
import { clearDraftRef } from './draft-storage';
import { SimulationStepComponent } from './simulation-step.component';
import { expectNoA11yViolations } from '../testing/axe';

const PRODUCT: DraftView['product'] = {
  code: 'EQUILIBRE',
  name: 'Horizon Equilibre',
  profile: 'Equilibre',
  description: 'Support equilibre.',
  annualRate: '0.040000',
};

const SIMULATION: SimulationResult = {
  initialAmount: '2500.00',
  monthlyAmount: '250.00',
  durationYears: 12,
  contributed: '38500.00',
  finalCapital: '42000.00',
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
  simulation: SIMULATION,
  confirmationKey: '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed',
  status: 'SIMULATED',
};

class SimulationApiStub {
  readonly requests: SimulationRequest[] = [];

  simulate(input: SimulationRequest): Observable<SimulationResult> {
    this.requests.push(input);
    return of({ ...SIMULATION, finalCapital: '43000.00' });
  }
}

class SubscriptionApiStub {
  readonly saved: SimulationInput[] = [];

  putDraftSimulation(draftId: string, input: SimulationInput): Observable<DraftView> {
    this.saved.push(input);
    return of(VIEW);
  }
}

describe('SimulationStepComponent', () => {
  let simulations: SimulationApiStub;
  let subscriptions: SubscriptionApiStub;

  beforeEach(() => {
    clearDraftRef();
    simulations = new SimulationApiStub();
    subscriptions = new SubscriptionApiStub();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        ...localeProviders,
        provideRouter([]),
        { provide: SimulationApiService, useValue: simulations },
        { provide: SubscriptionApiService, useValue: subscriptions },
      ],
    });
  });

  async function render(): Promise<{
    host: HTMLElement;
    component: SimulationStepComponent;
    stable: () => Promise<void>;
  }> {
    const fixture = TestBed.createComponent(SimulationStepComponent);
    await fixture.whenStable();
    return {
      host: fixture.nativeElement as HTMLElement,
      component: fixture.componentInstance,
      stable: async () => {
        await fixture.whenStable();
      },
    };
  }

  function field(host: HTMLElement, id: string): HTMLInputElement {
    const input = host.querySelector<HTMLInputElement>(`input#${id}`);
    if (input === null) {
      throw new Error(`Champ ${id} absent du formulaire.`);
    }
    return input;
  }

  it('carries the default values of the legacy SubscriptionForm', async () => {
    TestBed.inject(DraftStore).start(PRODUCT, VIEW.draftId, VIEW.confirmationKey);

    const { host } = await render();

    expect(field(host, 'initial').value).toBe('1000,00');
    expect(field(host, 'monthly').value).toBe('100,00');
    expect(field(host, 'years').value).toBe('5');
  });

  it('re-entering step 3 repopulates the group from the stored draft', async () => {
    TestBed.inject(DraftStore).apply(VIEW);

    const { host } = await render();

    expect(field(host, 'initial').value).toBe('2500,00');
    expect(field(host, 'monthly').value).toBe('250,00');
    expect(field(host, 'years').value).toBe('12');
  });

  it('falls back to the defaults when the draft carries null amounts', async () => {
    TestBed.inject(DraftStore).apply({ ...VIEW, simulation: null });

    const { host } = await render();

    expect(field(host, 'initial').value).toBe('1000,00');
    expect(field(host, 'monthly').value).toBe('100,00');
    expect(field(host, 'years').value).toBe('5');
  });

  it('keeps the maxlength, size, inputmode and aria attributes of simulation view', async () => {
    TestBed.inject(DraftStore).apply(VIEW);

    const { host } = await render();

    expect(field(host, 'initial').getAttribute('maxlength')).toBe('9');
    expect(field(host, 'initial').getAttribute('size')).toBe('14');
    expect(field(host, 'initial').getAttribute('inputmode')).toBe('decimal');
    expect(field(host, 'monthly').getAttribute('maxlength')).toBe('9');
    expect(field(host, 'monthly').getAttribute('size')).toBe('14');
    expect(field(host, 'monthly').getAttribute('inputmode')).toBe('decimal');
    expect(field(host, 'years').getAttribute('maxlength')).toBe('2');
    expect(field(host, 'years').getAttribute('size')).toBe('4');
    expect(field(host, 'years').getAttribute('inputmode')).toBe('numeric');
    for (const id of ['initial', 'monthly', 'years']) {
      expect(field(host, id).hasAttribute('required')).toBe(true);
      expect(field(host, id).getAttribute('aria-describedby')).toBe(`${id}-hint ${id}-error`);
      expect(field(host, id).getAttribute('aria-invalid')).toBe('false');
    }
    expect(host.querySelector('#initial-hint')?.textContent).toBe('De 100,00 à 100 000,00 €.');
    expect(host.querySelector('#monthly-hint')?.textContent).toBe(
      'De 0,00 à 2 000,00 €, en fin de mois.',
    );
    expect(host.querySelector('#years-hint')?.textContent).toBe(
      'Un nombre entier de 1 à 30 ans.',
    );
  });

  it('recalculate issues one simulate call, refreshes the result and does not navigate', async () => {
    TestBed.inject(DraftStore).apply(VIEW);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const { component, host, stable } = await render();
    component.recalculate();
    await stable();

    expect(simulations.requests).toEqual([
      {
        productCode: 'EQUILIBRE',
        initialAmount: '2500.00',
        monthlyAmount: '250.00',
        durationYears: 12,
      },
    ]);
    expect(subscriptions.saved).toEqual([]);
    expect(navigate).not.toHaveBeenCalled();
    expect(host.querySelector('#capital-final')?.textContent).toContain('43');
  });

  it('continue persists the simulation, then navigates to the summary', async () => {
    TestBed.inject(DraftStore).apply(VIEW);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const { component } = await render();
    component.continue();

    expect(subscriptions.saved).toEqual([
      { initialAmount: '2500.00', monthlyAmount: '250.00', durationYears: 12 },
    ]);
    expect(simulations.requests).toEqual([]);
    expect(navigate).toHaveBeenCalledWith(['/souscription/recapitulatif']);
  });

  it('renders no hidden CSRF field: the token is carried by the interceptor', async () => {
    TestBed.inject(DraftStore).apply(VIEW);

    const { host } = await render();

    expect(host.querySelector('input[type="hidden"]')).toBeNull();
  });
  it('renders the simulation step without serious or critical accessibility violation', async () => {
    TestBed.inject(DraftStore).apply(VIEW);

    const { host } = await render();

    await expectNoA11yViolations(host);
  });
});
