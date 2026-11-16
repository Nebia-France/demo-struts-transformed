import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import type { AppError } from '../error/app-error';
import { errorPageUrlFor } from '../error/app-error';
import { serverFieldErrors, subscriberFormGroup } from '../../wizard/form-models';
import { errorInterceptor } from './error.interceptor';

const FIELD_PROBLEM = {
  type: 'about:blank',
  title: 'Validation échouée',
  status: 400,
  errors: [{ field: 'email', message: "L'adresse électronique est obligatoire.", code: 'required' }],
};

describe('errorInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  /** Issues one call and returns the mapped error, after answering with `status` and `body`. */
  function failWith(status: number, body: object | null): AppError {
    let error: AppError | undefined;
    http.get('/api/v1/draft').subscribe({ error: (e: AppError) => (error = e) });
    const request = httpMock.expectOne('/api/v1/draft');
    if (status === 0) {
      request.error(new ProgressEvent('error'));
    } else {
      request.flush(body, { status, statusText: `status ${status}` });
    }
    expect(error).toBeDefined();
    return error!;
  }

  it('maps status 0 (offline, aborted) to the network family', () => {
    expect(failWith(0, null)).toEqual({ kind: 'network' });
  });

  it('maps 400 carrying field errors to the field family, keeping the problem document', () => {
    expect(failWith(400, FIELD_PROBLEM)).toEqual({ kind: 'field', fields: FIELD_PROBLEM });
  });

  it('maps 400 without field errors to the http family', () => {
    const problem = { type: 'about:blank', title: 'Requête invalide', status: 400, detail: 'Corps illisible.' };
    expect(failWith(400, problem)).toEqual({ kind: 'http', status: 400, detail: 'Corps illisible.' });
  });

  it('maps 403 (CSRF rejected) to the http family', () => {
    expect(failWith(403, { title: 'Interdit', status: 403 })).toEqual({
      kind: 'http',
      status: 403,
      detail: undefined,
    });
  });

  it('maps 404 to the expired family', () => {
    expect(failWith(404, { title: 'Introuvable', status: 404 })).toEqual({ kind: 'expired' });
  });

  it('maps 405 to the http family', () => {
    expect(failWith(405, { title: 'Méthode non autorisée', status: 405 })).toEqual({
      kind: 'http',
      status: 405,
      detail: undefined,
    });
  });

  it('maps 409 to the conflict family', () => {
    expect(failWith(409, { title: 'Conflit', status: 409 })).toEqual({ kind: 'conflict' });
  });

  it('maps 410 to the expired family', () => {
    expect(failWith(410, { title: 'Parti', status: 410 })).toEqual({ kind: 'expired' });
  });

  it('maps 500 to the http family', () => {
    expect(failWith(500, { title: 'Erreur interne', status: 500 })).toEqual({
      kind: 'http',
      status: 500,
      detail: undefined,
    });
  });

  it('maps 503 to the http family', () => {
    expect(failWith(503, { title: 'Indisponible', status: 503, detail: 'Enregistrement indisponible.' })).toEqual({
      kind: 'http',
      status: 503,
      detail: 'Enregistrement indisponible.',
    });
  });

  it('never navigates on a field error and keeps the typed values', () => {
    const form = subscriberFormGroup(TestBed.inject(FormBuilder).nonNullable);
    form.setValue({
      firstName: 'Camille',
      lastName: 'Durand',
      email: 'camille.durand',
      birthDate: '14/03/1985',
    });
    const error = failWith(400, FIELD_PROBLEM);
    // The legacy tier redisplayed the `souscripteur` view with `${errors.email}`: no navigation.
    expect(errorPageUrlFor(error)).toBeNull();
    expect(serverFieldErrors(error)).toEqual({
      email: "L'adresse électronique est obligatoire.",
    });
    expect(form.getRawValue()).toEqual({
      firstName: 'Camille',
      lastName: 'Durand',
      email: 'camille.durand',
      birthDate: '14/03/1985',
    });
  });

  it('never replays the request it mapped', () => {
    http.get('/api/v1/draft').subscribe({ error: () => undefined });
    const pending = httpMock.match('/api/v1/draft');
    expect(pending.length).toBe(1);
    pending[0].flush(null, { status: 500, statusText: 'status 500' });
    expect(httpMock.match('/api/v1/draft').length).toBe(0);
  });
});
