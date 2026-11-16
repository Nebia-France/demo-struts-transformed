import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CatalogueApiService } from '../api/catalogue-api.service';
import type { ProductDetail, ProductSummary } from '../api/models';
import { CatalogueFacade } from './catalogue.facade';

describe('CatalogueFacade', () => {
  let facade: CatalogueFacade;
  let api: CatalogueApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    facade = TestBed.inject(CatalogueFacade);
    api = TestBed.inject(CatalogueApiService);
  });

  it('is a root-provided singleton reached by class token', () => {
    expect(TestBed.inject(CatalogueFacade)).toBe(facade);
  });

  it('delegates products() to listProducts', () => {
    const products: ProductSummary[] = [
      { code: 'PRUDENT', name: 'Horizon Prudent', profile: 'Prudent', annualRate: '0.020000' },
    ];
    const spy = vi.spyOn(api, 'listProducts').mockReturnValue(of(products));

    let received: ProductSummary[] | undefined;
    facade.products().subscribe((r) => (received = r));

    expect(spy).toHaveBeenCalledTimes(1);
    expect(received).toBe(products);
  });

  it('delegates products(code) to getProduct', () => {
    const product: ProductDetail = {
      code: 'DYNAMIQUE',
      name: 'Horizon Dynamique',
      profile: 'Dynamique',
      description: 'Support actions.',
      annualRate: '0.060000',
    };
    const spy = vi.spyOn(api, 'getProduct').mockReturnValue(of(product));

    let received: ProductDetail | undefined;
    facade.products('DYNAMIQUE').subscribe((r) => (received = r));

    expect(spy).toHaveBeenCalledWith('DYNAMIQUE');
    expect(received).toBe(product);
  });

  it('holds no mutable state beyond the injected api service', () => {
    vi.spyOn(api, 'listProducts').mockReturnValue(of([]));
    facade.products().subscribe();

    expect(Object.getOwnPropertyNames(facade)).toEqual(['api']);
  });
});
