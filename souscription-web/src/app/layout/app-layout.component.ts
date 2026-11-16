import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import type { ActivatedRouteSnapshot, Data } from '@angular/router';
import { filter } from 'rxjs';
import { BreadcrumbComponent } from './breadcrumb.component';
import { LAYOUT_LABELS } from './labels';
import { SiteFooterComponent } from './site-footer.component';
import { SiteHeaderComponent } from './site-header.component';
import { WizardStepsComponent } from './wizard-steps.component';

/** Deepest activated child, which carries the `step` / `title` route data. */
function deepestSnapshot(snapshot: ActivatedRouteSnapshot): ActivatedRouteSnapshot {
  let current = snapshot;
  while (current.firstChild) {
    current = current.firstChild;
  }
  return current;
}

/**
 * Single layout component replacing the `header` / `footer` fragment pair: the fragments
 * opened markup in one file and closed it in another, so the structure is held here as a
 * whole and `<router-outlet />` sits exactly where the `<main id="contenu">` body was.
 *
 * `step` and `pageTitle` come from the route `data`, replacing the
 * `request.setAttribute("step", n)` / `request.setAttribute("pageTitle", ...)` calls of the
 * legacy actions. The document title itself is set by `dataTitleStrategy` (app.config.ts).
 */
@Component({
  selector: 'app-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    SiteHeaderComponent,
    SiteFooterComponent,
    WizardStepsComponent,
    BreadcrumbComponent,
  ],
  templateUrl: './app-layout.component.html',
  styleUrl: './app-layout.component.scss',
})
export class AppLayoutComponent {
  /** W4-T08: every literal of the layout templates comes from the feature label module. */
  readonly labels = LAYOUT_LABELS;

  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly routeData = signal<Data>(deepestSnapshot(this.route.snapshot).data);

  constructor() {
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.routeData.set(deepestSnapshot(this.route.snapshot).data));
  }

  /** Former `step` request attribute; 0 when the route is outside the funnel. */
  readonly step = computed(() => Number(this.routeData()['step'] ?? 0));

  /** Former `pageTitle` request attribute, with the same `default="Information"`. */
  readonly pageTitle = computed(() =>
    String(this.routeData()['title'] ?? LAYOUT_LABELS.defaultPageTitle),
  );

  /**
   * W4-T07: a page reload announced the new screen for free; the SPA announces it through a
   * `role="status"` live region fed by the same route title as the `h1` and the title strategy.
   */
  readonly routeAnnouncement = computed(() => LAYOUT_LABELS.routeAnnouncement(this.pageTitle()));
}
