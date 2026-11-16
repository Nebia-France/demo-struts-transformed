import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CatalogueApiService } from './catalogue-api.service';
import type { ProductDetail, ProductSummary } from './models';

describe('CatalogueApiService', () => {
  let service: CatalogueApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CatalogueApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the catalogue', () => {
    const products: ProductSummary[] = [
      { code: 'PRUDENT', name: 'Horizon Prudent', profile: 'Prudent', annualRate: '0.020000' },
    ];
    let received: ProductSummary[] | undefined;
    service.listProducts().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/v1/products');
    expect(req.request.method).toBe('GET');
    req.flush(products);
    expect(received).toEqual(products);
  });

  it('GETs one product', () => {
    const product: ProductDetail = {
      code: 'PRUDENT',
      name: 'Horizon Prudent',
      profile: 'Prudent',
      description: 'Support sécurisé.',
      annualRate: '0.020000',
    };
    let received: ProductDetail | undefined;
    service.getProduct('PRUDENT').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/v1/products/PRUDENT');
    expect(req.request.method).toBe('GET');
    req.flush(product);
    expect(received).toEqual(product);
  });

  it('encodes the product code path segment', () => {
    service.getProduct('a/b').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/v1/products/a%2Fb').flush(null, { status: 404, statusText: 'Not Found' });
  });

  it('propagates errors', () => {
    let error: HttpErrorResponse | undefined;
    service.listProducts().subscribe({ error: (e: HttpErrorResponse) => (error = e) });

    httpMock
      .expectOne('/api/v1/products')
      .flush({ title: 'Service Unavailable', status: 503 }, { status: 503, statusText: 'Service Unavailable' });

    expect(error?.status).toBe(503);
  });
});
