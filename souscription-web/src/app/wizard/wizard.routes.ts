import { Routes } from '@angular/router';
import { httpProviders } from '../core/http.providers';
import {
  requireConfirmation,
  requireDraft,
  requireSimulation,
  requireSubscriber,
} from './wizard.guards';
import { WIZARD_LABELS } from './labels';

/**
 * Child routes of `/souscription`, replacing the four GET action-mappings of the legacy
 * wizard (`souscripteur`, `simulation`, `recapitulatif`, `confirmation`).
 * `data.step` (2..5, the catalogue being step 1) and `data.title` drive the step rail and the
 * title strategy (plan 13).
 * Every component is lazily loaded so the wizard stays out of the initial bundle.
 */
export const wizardRoutes: Routes = [
  {
    path: '',
    providers: [...httpProviders],
    loadComponent: () => import('./wizard-shell.component').then((m) => m.WizardShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'souscripteur' },
      {
        path: 'souscripteur',
        data: { step: 2, title: WIZARD_LABELS.subscriberRouteTitle },
        canActivate: [requireDraft],
        loadComponent: () =>
          import('./subscriber-step.component').then((m) => m.SubscriberStepComponent),
      },
      {
        path: 'simulation',
        data: { step: 3, title: WIZARD_LABELS.simulationRouteTitle },
        canActivate: [requireSubscriber],
        loadComponent: () =>
          import('./simulation-step.component').then((m) => m.SimulationStepComponent),
      },
      {
        path: 'recapitulatif',
        data: { step: 4, title: WIZARD_LABELS.summaryRouteTitle },
        canActivate: [requireSimulation],
        loadComponent: () => import('./summary-step.component').then((m) => m.SummaryStepComponent),
      },
      {
        path: 'confirmation',
        data: { step: 5, title: WIZARD_LABELS.confirmationRouteTitle },
        canActivate: [requireConfirmation],
        loadComponent: () =>
          import('./confirmation-step.component').then((m) => m.ConfirmationStepComponent),
      },
    ],
  },
];
