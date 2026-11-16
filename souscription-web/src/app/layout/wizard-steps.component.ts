import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { LAYOUT_LABELS } from './labels';

/**
 * Step rail of the former `header` fragment sidebar. The JSP read the implicit `step` request
 * attribute set by each action (`request.setAttribute("step", n)`); here it is a signal
 * input fed from the route `data.step` by `AppLayoutComponent`.
 */
@Component({
  selector: 'app-wizard-steps',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './wizard-steps.component.html',
  styleUrl: './wizard-steps.component.scss',
})
export class WizardStepsComponent {
  readonly labels = LAYOUT_LABELS;

  /** The five rail entries, in source order, with the `step` value each one matches. */
  readonly steps = [
    { index: 1, text: LAYOUT_LABELS.step1 },
    { index: 2, text: LAYOUT_LABELS.step2 },
    { index: 3, text: LAYOUT_LABELS.step3 },
    { index: 4, text: LAYOUT_LABELS.step4 },
    { index: 5, text: LAYOUT_LABELS.step5 },
  ] as const;

  /** 1 = Choix du produit ... 5 = Confirmation; 0 marks no current step. */
  readonly step = input<number>(0);
}
