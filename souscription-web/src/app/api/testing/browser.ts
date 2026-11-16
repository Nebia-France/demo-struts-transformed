import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

/**
 * Browser worker serving the gateway contract stub. Started only when
 * NG_APP_USE_GATEWAY_STUB is 'true' (see src/main.ts); it requires
 * `npx msw init public/ --save` to have written the service-worker script.
 */
export const worker = setupWorker(...handlers);
