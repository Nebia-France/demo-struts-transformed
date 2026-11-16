import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ERROR_LABELS } from '../../core/error/labels';
import { ErrorSummaryComponent } from './error-summary.component';

/** Replaces the `#erreurs` / `.error li` jsoup assertions of SubscriptionIT. */
describe('ErrorSummaryComponent (errors fragment)', () => {
  const render = (messages: readonly string[]) => {
    const fixture = TestBed.createComponent(ErrorSummaryComponent);
    fixture.componentRef.setInput('serverErrors', messages);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  it('renders nothing when there is no error', () => {
    expect(render([]).querySelector('#erreurs')).toBeNull();
  });

  it('renders #erreurs as an alert with one .error li per message, in order', () => {
    const messages = ['Le nom est obligatoire.', 'Le prénom est obligatoire.', "L'email est invalide."];
    const host = render(messages);

    const summary = host.querySelector('#erreurs');
    expect(summary).not.toBeNull();
    expect(summary?.getAttribute('role')).toBe('alert');
    expect(summary?.querySelector('strong')?.textContent).toBe(ERROR_LABELS.summaryTitle);
    const items = host.querySelectorAll('.error li');
    expect(items.length).toBe(3);
    expect(Array.from(items, (li) => li.textContent)).toEqual(messages);
  });

  it('never interprets a server message as HTML', () => {
    const host = render(['<em>injecté</em>']);

    expect(host.querySelector('#erreurs em')).toBeNull();
    expect(host.querySelector('#erreurs li')?.textContent).toBe('<em>injecté</em>');
  });
});
