import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { TitleStrategy } from '@angular/router';
import type { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';

/**
 * Replaces `<title><c:out value="${pageTitle}" default="Information"/> | Financière Horizon</title>`
 * of the `header` fragment: the title comes from the route `data.title` instead of the `pageTitle`
 * request attribute set by each legacy action.
 */
@Injectable({ providedIn: 'root' })
export class DataTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);

  override updateTitle(state: RouterStateSnapshot): void {
    let route: ActivatedRouteSnapshot = state.root;
    while (route.firstChild) {
      route = route.firstChild;
    }
    const pageTitle = route.data['title'] ?? 'Information';
    this.title.setTitle(`${pageTitle} | Financière Horizon`);
  }
}
