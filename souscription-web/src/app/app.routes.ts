import { Routes } from '@angular/router';
import { CatalogueListComponent } from './catalogue/catalogue-list.component';
import { catalogueResolver } from './catalogue/catalogue.resolvers';
import { CATALOGUE_LABELS } from './catalogue/labels';
import { ERROR_LABELS } from './core/error/labels';
import { httpProviders } from './core/http.providers';

/**
 * Router configuration replacing the legacy front-controller configuration (6 global-forwards,
 * 8 action-mappings) and its servlet mapping in `web.xml`. The action-by-action correspondence
 * table is kept in README.md, section "Route correspondence"; `app.routes.spec.ts` asserts
 * one row each.
 *
 * Six feature routes (`/catalogue`, `/produit/:code` and the four `/souscription/*` steps of
 * `wizard.routes.ts`) plus the error route `/erreur/:code`. The two POST mappings have no route:
 * they are an API call followed by a navigation.
 *
 * `/catalogue` is the landing route and the route whose LCP is measured: it stays in the initial
 * bundle (eager `component`), so no chunk request sits between bootstrap and the first paint.
 * Every other route, including the five wizard steps, is loaded lazily.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'catalogue' },
  {
    path: 'catalogue',
    providers: [...httpProviders],
    resolve: { products: catalogueResolver },
    data: { step: 1, title: CATALOGUE_LABELS.routeTitle },
    component: CatalogueListComponent,
  },
  // Former `produit` action without a code: the product sheet answered 404, never a blank screen.
  { path: 'produit', pathMatch: 'full', redirectTo: 'erreur/404' },
  {
    path: 'produit',
    loadChildren: () => import('./catalogue/catalogue.routes').then((m) => m.productRoutes),
  },
  {
    path: 'souscription',
    loadChildren: () => import('./wizard/wizard.routes').then((m) => m.wizardRoutes),
  },
  {
    path: 'erreur/:code',
    data: { step: 0, title: ERROR_LABELS.pageTitle },
    loadComponent: () => import('./core/error/error-page.component').then((m) => m.ErrorPageComponent),
  },
  { path: '**', redirectTo: 'erreur/404' },
];
