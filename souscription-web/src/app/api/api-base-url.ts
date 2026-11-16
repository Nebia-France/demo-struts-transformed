/**
 * Root of the public gateway contract. Relative on purpose: the SPA and
 * souscription-gateway are served from the same origin behind the shared ingress
 * (decision OD-004), so no absolute URL and no CORS configuration are involved.
 */
export const API_BASE_URL = '/api/v1';
