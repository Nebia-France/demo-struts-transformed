import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, convertToParamMap } from '@angular/router';
import { Observable, of } from 'rxjs';
import { CatalogueApiService } from '../api/catalogue-api.service';
import type { ProductDetail, ProductSummary } from '../api/models';
import { catalogueResolver, productResolver } from './catalogue.resolvers';

const CATALOGUE: ProductSummary[] = [
  { code: 'PRUDENT', name: 'Horizon Prudent', profile: 'Prudent', annualRate: '0.020000' },
];

const PRODUCT: ProductDetail = {
  code: 'PRUDENT',
  name: 'Horizon Prudent',
  profile: 'Prudent',
  description: 'Support sécurisé.',
  annualRate: '0.020000',
};

class CatalogueApiStub {
  listProductsCalls = 0;
  getProductCodes: string[] = [];

  listProducts(): Observable<ProductSummary[]> {
    this.listProductsCalls += 1;
    return of(CATALOGUE);
  }

  getProduct(code: string): Observable<ProductDetail> {
    this.getProductCodes.push(code);
    return of(PRODUCT);
  }
}

function snapshotWithCode(code: string): ActivatedRouteSnapshot {
  return { paramMap: convertToParamMap({ code }) } as unknown as ActivatedRouteSnapshot;
}

const STATE = {} as RouterStateSnapshot;

describe('catalogue resolvers', () => {
  let api: CatalogueApiStub;

  beforeEach(() => {
    api = new CatalogueApiStub();
    TestBed.configureTestingModule({
      providers: [{ provide: CatalogueApiService, useValue: api }],
    });
  });

  it('catalogueResolver calls listProducts exactly once', () => {
    let received: ProductSummary[] | undefined;
    const result = TestBed.runInInjectionContext(() =>
      catalogueResolver(snapshotWithCode(''), STATE),
    ) as Observable<ProductSummary[]>;
    result.subscribe((r) => (received = r));

    expect(api.listProductsCalls).toBe(1);
    expect(received).toEqual(CATALOGUE);
  });

  it('productResolver forwards the code route parameter verbatim', () => {
    let received: ProductDetail | undefined;
    const result = TestBed.runInInjectionContext(() =>
      productResolver(snapshotWithCode('PRUDENT'), STATE),
    ) as Observable<ProductDetail>;
    result.subscribe((r) => (received = r));

    expect(api.getProductCodes).toEqual(['PRUDENT']);
    expect(received).toEqual(PRODUCT);
  });

  it('productResolver does not pre-encode a code needing URL encoding', () => {
    TestBed.runInInjectionContext(() => productResolver(snapshotWithCode('a/b'), STATE));

    expect(api.getProductCodes).toEqual(['a/b']);
  });
});
