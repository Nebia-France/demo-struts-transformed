import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TelemetryService } from '../telemetry/telemetry.service';
import { GlobalErrorHandler } from './global-error.handler';

class TelemetryStub {
  readonly reported: unknown[] = [];
  reportError(error: unknown): void {
    this.reported.push(error);
  }
}

describe('GlobalErrorHandler (W5-T04, replaces the <error-page> forwards)', () => {
  let telemetry: TelemetryStub;
  let navigated: { url: string; skipLocationChange: boolean | undefined }[];

  beforeEach(() => {
    telemetry = new TelemetryStub();
    navigated = [];
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: 'erreur/:status', children: [] }]),
        { provide: TelemetryService, useValue: telemetry },
        GlobalErrorHandler,
      ],
    });
    const router = TestBed.inject(Router);
    router.navigateByUrl = (url: unknown, extras?: { skipLocationChange?: boolean }) => {
      navigated.push({ url: String(url), skipLocationChange: extras?.skipLocationChange });
      return Promise.resolve(true);
    };
  });

  it('routes an uncaught error to /erreur/500 without changing the location', () => {
    TestBed.inject(GlobalErrorHandler).handleError(new Error('boom'));

    expect(navigated).toEqual([{ url: '/erreur/500', skipLocationChange: true }]);
  });

  it('reports the error once to the telemetry sink', () => {
    const failure = new Error('boom');

    TestBed.inject(GlobalErrorHandler).handleError(failure);

    expect(telemetry.reported).toEqual([failure]);
  });

  it('keeps the status of an http failure that escaped a component', () => {
    TestBed.inject(GlobalErrorHandler).handleError({ kind: 'http', status: 403 });

    expect(navigated[0].url).toBe('/erreur/403');
  });
});
