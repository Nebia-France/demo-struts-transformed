import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import type { Routes } from '@angular/router';
import { AppLayoutComponent } from './app-layout.component';

/** Stands in for the routed page components, which carry no `h1` of their own. */
@Component({ selector: 'app-step-page', template: '' })
class StepPageComponent {}

/** One route per step of the legacy rail, carrying the `data` of plans 11 and 13. */
const ROUTES: Routes = [
  { path: 'catalogue', data: { step: 1, title: 'Catalogue des produits' }, component: StepPageComponent },
  { path: 'souscripteur', data: { step: 2, title: 'Souscripteur' }, component: StepPageComponent },
  { path: 'simulation', data: { step: 3, title: 'Simulation' }, component: StepPageComponent },
  { path: 'recapitulatif', data: { step: 4, title: 'Récapitulatif' }, component: StepPageComponent },
  { path: 'confirmation', data: { step: 5, title: 'Confirmation' }, component: StepPageComponent },
];

const LABELS = [
  'Choix du produit',
  'Souscripteur',
  'Simulation',
  'Récapitulatif',
  'Confirmation',
];

describe('AppLayoutComponent (header fragment / footer fragment port)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter(ROUTES)],
    });
  });

  async function render(url: string): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(AppLayoutComponent);
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  for (const [index, path] of ROUTES.map((route) => route.path as string).entries()) {
    const step = index + 1;

    it(`marks step ${step} as the current item of the rail`, async () => {
      const host = await render(`/${path}`);
      const items = Array.from(host.querySelectorAll('ol.steps li'));

      expect(items.length).toBe(5);
      expect(items.filter((li) => li.classList.contains('current')).length).toBe(1);
      expect(items[index].classList.contains('current')).toBe(true);
      expect(items[index].textContent).toContain(LABELS[index]);
    });

    it(`renders the sr-only current-step marker on step ${step}`, async () => {
      const host = await render(`/${path}`);
      const markers = host.querySelectorAll('ol.steps li .sr-only');

      expect(markers.length).toBe(1);
      expect(markers[0].textContent).toBe('Étape actuelle : ');
      expect(markers[0].parentElement).toBe(host.querySelectorAll('ol.steps li')[index]);
    });

    it(`renders exactly one h1 on step ${step}`, async () => {
      const host = await render(`/${path}`);
      const headings = host.querySelectorAll('h1');

      expect(headings.length).toBe(1);
      expect(headings[0].textContent).toBe(ROUTES[index].data!['title']);
    });

    it(`keeps the skip-link pointing to #contenu on step ${step}`, async () => {
      const host = await render(`/${path}`);
      const skip = host.querySelector('a.skip-link');

      expect(skip?.getAttribute('href')).toBe('#contenu');
      expect(host.querySelector('main')?.id).toBe('contenu');
    });

    it(`renders the breadcrumb on step ${step}`, async () => {
      const host = await render(`/${path}`);
      const breadcrumb = host.querySelector('div.breadcrumb');

      expect(breadcrumb).not.toBeNull();
      expect(breadcrumb?.textContent).toContain('Accueil');
      expect(breadcrumb?.textContent).toContain('Épargne');
      expect(breadcrumb?.textContent).toContain(ROUTES[index].data!['title']);
    });
  }

  it('selects the catalogue tab on step 1 only', async () => {
    expect((await render('/catalogue')).querySelector('nav.tabs a')?.classList).toContain(
      'selected',
    );
    expect((await render('/simulation')).querySelector('nav.tabs a')?.classList).not.toContain(
      'selected',
    );
  });

  it('renders the router-outlet inside main#contenu and the footer once', async () => {
    const host = await render('/catalogue');

    expect(host.querySelectorAll('footer').length).toBe(1);
    expect(host.querySelector('main#contenu')).not.toBeNull();
  });
});
