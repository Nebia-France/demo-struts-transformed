import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { localeProviders } from '../core/locale/locale.providers';
import { SimulationResultComponent } from './simulation-result.component';

/** fr-FR formatting uses non-breaking spaces; comparisons normalise them to plain spaces. */
const normalise = (value: string | null | undefined): string =>
  (value ?? '').replace(/[\u00A0\u202F]/g, ' ').trim();

describe('SimulationResultComponent (result fragment)', () => {
  const render = () => {
    const fixture = TestBed.createComponent(SimulationResultComponent);
    fixture.componentRef.setInput('initial', '2500.00');
    fixture.componentRef.setInput('monthly', '250.00');
    fixture.componentRef.setInput('years', '12');
    fixture.componentRef.setInput('contributed', '38500.00');
    fixture.componentRef.setInput('gains', '3500.00');
    fixture.componentRef.setInput('finalCapital', '42000.00');
    fixture.componentRef.setInput('ratePercent', '4');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), ...localeProviders],
    });
  });

  it('keeps the caption of the financial table', () => {
    expect(normalise(render().querySelector('table.data-table.financial caption')?.textContent)).toBe(
      'Résultat indicatif de la simulation',
    );
  });

  it('renders the six row headers of the source fragment, all scoped to their row', () => {
    const headers = Array.from(render().querySelectorAll('tbody th'));

    expect(headers.map((header) => normalise(header.textContent))).toEqual([
      'Versement initial',
      'Versement mensuel',
      'Durée du placement',
      'Total de vos versements',
      'Gains estimés, hors frais et fiscalité',
      'Capital final estimé',
    ]);
    expect(headers.every((header) => header.getAttribute('scope') === 'row')).toBe(true);
  });

  it('formats every amount in fr-FR and keeps #capital-final on the total row', () => {
    const element = render();
    const cells = Array.from(element.querySelectorAll('tbody td')).map((cell) =>
      normalise(cell.textContent),
    );

    expect(cells).toEqual([
      '2 500,00 €',
      '250,00 €',
      '12 an(s)',
      '38 500,00 €',
      '3 500,00 €',
      '42 000,00 €',
    ]);
    expect(normalise(element.querySelector('#capital-final')?.textContent)).toBe('42 000,00 €');
    expect(element.querySelector('tr.total #capital-final')).not.toBeNull();
  });

  it('keeps the assumption note with the rate rendered without decimals', () => {
    expect(normalise(render().querySelector('p.fine-print')?.textContent)).toContain(
      'Hypothèse constante de 4 % par an',
    );
  });
});
