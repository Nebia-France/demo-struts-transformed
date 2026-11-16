import { Provider, EnvironmentProviders } from '@angular/core';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { XSRF_CONFIG, httpInterceptorChain } from '../app.config';

/**
 * HttpClient providers for the feature routes that call the gateway.
 * The interceptor chain enforces the resilience contract (10 s timeout, 0 retry,
 * bulkhead of 6 concurrent calls); the final order is the built-in XSRF handling -> timeout -> error.
 */
export const httpProviders: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(withInterceptors(httpInterceptorChain), withXsrfConfiguration(XSRF_CONFIG)),
];
