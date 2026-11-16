import { Routes } from '@angular/router';
import { httpProviders } from '../core/http.providers';
import { productResolver } from './catalogue.resolvers';
import { CATALOGUE_LABELS } from './labels';

/**
 * Child routes of `/produit`, replacing the `produit?code=X` action-mapping.
 */
export const productRoutes: Routes = [
  {
    path: ':code',
    providers: [...httpProviders],
    resolve: { product: productResolver },
    data: { step: 1, title: CATALOGUE_LABELS.productRouteTitle },
    loadComponent: () => import('./product-detail.component').then((m) => m.ProductDetailComponent),
  },
];
