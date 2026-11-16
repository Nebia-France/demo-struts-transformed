import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { WIZARD_LABELS } from '../wizard/labels';

/**
 * Structural port of the `result` fragment. Amounts stay decimal strings (money is transported and held
 * as a string, never an IEEE-754 `number`); the `currency` and `number` pipes format them for
 * display only, replacing `fmt:formatNumber type="currency" currencySymbol="€"` and the
 * `maxFractionDigits="0"` rate. The `#capital-final` cell id is preserved.
 */
@Component({
  selector: 'app-simulation-result',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, DecimalPipe],
  templateUrl: './simulation-result.component.html',
  styleUrl: './simulation-result.component.scss',
})
export class SimulationResultComponent {
  readonly labels = WIZARD_LABELS;

  readonly initial = input<string>('');
  readonly monthly = input<string>('');
  readonly years = input<string>('');
  readonly contributed = input<string>('');
  readonly gains = input<string>('');
  readonly finalCapital = input<string>('');
  /** Annual rate in percent, as rendered by the former `fmt:formatNumber`. */
  readonly ratePercent = input<string>('');
}
