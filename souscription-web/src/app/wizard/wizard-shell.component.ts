import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Placeholder wizard shell; the step rail lands in plan 13. */
@Component({
  selector: 'app-wizard-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class WizardShellComponent {}
