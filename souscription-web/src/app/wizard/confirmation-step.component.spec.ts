import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { DraftView, SubscriptionConfirmation } from '../api/models';
import { localeProviders } from '../core/locale/locale.providers';
import { ConfirmationStepComponent } from './confirmation-step.component';
import { DraftStore } from './draft.store';
import { clearDraftRef } from './draft-storage';
import { expectNoA11yViolations } from '../testing/axe';

const normalise = (value: string | null | undefined): string =>
  (value ?? '').replace(/[\u00A0\u202F]/g, ' ').trim();

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

const CONFIRMATION: SubscriptionConfirmation = {
  reference: 'SUB-2024-000123',
  product: {
    code: 'EQUILIBRE',
    name: 'Horizon Equilibre',
    profile: 'Equilibre',
    annualRate: '0.040000',
  },
  subscriber: VIEW.subscriber!,
  simulation: VIEW.simulation!,
  createdAt: '2024-05-01T09:30:00Z',
};

describe('ConfirmationStepComponent (confirmation view)', () => {
  let store: DraftStore;

  const render = () => {
    const fixture = TestBed.createComponent(ConfirmationStepComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(() => {
    clearDraftRef();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), ...localeProviders, provideRouter([])],
    });
    store = TestBed.inject(DraftStore);
    store.clear();
  });

  it('displays the reference, the Paris creation label, the product and the subscriber', () => {
    store.apply(VIEW);
    store.complete(CONFIRMATION);
    const element = render();

    expect(element.querySelector('.message.success')?.getAttribute('role')).toBe('status');
    expect(normalise(element.querySelector('#reference')?.textContent)).toBe('SUB-2024-000123');
    expect(
      Array.from(element.querySelectorAll('table.details td')).map((cell) =>
        normalise(cell.textContent),
      ),
    ).toEqual([
      'SUB-2024-000123',
      '01/05/2024 à 11:30 (Paris)',
      'Horizon Equilibre',
      'Camille Durand',
    ]);
  });

  it('embeds the shared result block of the confirmed simulation', () => {
    store.apply(VIEW);
    store.complete(CONFIRMATION);
    const element = render();

    expect(element.querySelector('app-simulation-result')).not.toBeNull();
    expect(normalise(element.querySelector('#capital-final')?.textContent)).toBe('42 000,00 €');
  });

  it('offers the return link to the catalogue', () => {
    store.apply(VIEW);
    store.complete(CONFIRMATION);

    expect(render().querySelector('a.button')?.getAttribute('href')).toBe('/catalogue');
  });

  it('displays no confirmation when no subscription was completed', () => {
    const element = render();

    expect(element.querySelector('#reference')).toBeNull();
    expect(normalise(element.textContent)).toBe('');
  });
  it('renders the confirmation step without serious or critical accessibility violation', async () => {
    store.apply(VIEW);
    store.complete(CONFIRMATION);

    await expectNoA11yViolations(render());
  });
});
