import { http, HttpResponse } from 'msw';
import { API_BASE_URL } from '../api-base-url';
import type {
  DraftView,
  ProductDetail,
  ProductSummary,
  SimulationResult,
  SubscriberInput,
  SubscriptionConfirmation,
} from '../models';

/**
 * Contract-shaped stub of the consumed provider `souscription-gateway` (/api/v1).
 * One handler per contract operation; every monetary value and the annual rate are
 * decimal strings, every date an ISO-8601 string, exactly as the contract requires.
 *
 * This is a development aid only, enabled by NG_APP_USE_GATEWAY_STUB (see src/main.ts).
 */

const PRODUCTS: ProductDetail[] = [
  {
    code: 'PRUDENT',
    name: 'Horizon Prudent',
    profile: 'Prudent',
    description: 'Support sécurisé, volatilité faible.',
    annualRate: '0.020000',
  },
  {
    code: 'EQUILIBRE',
    name: 'Horizon Équilibré',
    profile: 'Équilibré',
    description: 'Répartition équilibrée entre obligations et actions.',
    annualRate: '0.040000',
  },
  {
    code: 'DYNAMIQUE',
    name: 'Horizon Dynamique',
    profile: 'Dynamique',
    description: 'Exposition actions majoritaire, volatilité élevée.',
    annualRate: '0.070000',
  },
];

const summaryOf = (product: ProductDetail): ProductSummary => ({
  code: product.code,
  name: product.name,
  profile: product.profile,
  annualRate: product.annualRate,
});

const SIMULATION: SimulationResult = {
  initialAmount: '1000.00',
  monthlyAmount: '100.00',
  durationYears: 10,
  contributed: '13000.00',
  finalCapital: '15763.42',
};

const SUBSCRIBER: SubscriberInput = {
  firstName: 'Camille',
  lastName: 'Durand',
  email: 'camille.durand@example.com',
  birthDate: '1985-03-14',
};

const DRAFT_ID = '7f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d';
const CONFIRMATION_KEY = '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
const REFERENCE = 'SUB-2024-000001';

const notFound = (detail: string) =>
  HttpResponse.json(
    { type: 'about:blank', title: 'Not Found', status: 404, detail },
    { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
  );

const draftOf = (product: ProductDetail): DraftView => ({
  draftId: DRAFT_ID,
  product,
  subscriber: null,
  simulation: null,
  confirmationKey: CONFIRMATION_KEY,
  status: 'STARTED',
});

export const handlers = [
  http.get(`${API_BASE_URL}/csrf-token`, () =>
    HttpResponse.json({ token: 'stub-csrf-token-0123456789abcdef0123456789' }),
  ),

  http.get(`${API_BASE_URL}/products`, () => HttpResponse.json(PRODUCTS.map(summaryOf))),

  http.get(`${API_BASE_URL}/products/:code`, ({ params }) => {
    const product = PRODUCTS.find((p) => p.code === params['code']);
    return product ? HttpResponse.json(product) : notFound('Unknown product code.');
  }),

  http.post(`${API_BASE_URL}/simulations`, async ({ request }) => {
    const body = (await request.json()) as { productCode?: string };
    if (!PRODUCTS.some((p) => p.code === body.productCode)) {
      return notFound('Unknown product code.');
    }
    return HttpResponse.json(SIMULATION);
  }),

  http.post(`${API_BASE_URL}/drafts`, async ({ request }) => {
    const body = (await request.json()) as { productCode?: string };
    const product = PRODUCTS.find((p) => p.code === body.productCode);
    if (!product) {
      return notFound('Unknown product code.');
    }
    return HttpResponse.json(draftOf(product), {
      status: 201,
      headers: { Location: `${API_BASE_URL}/drafts/${DRAFT_ID}` },
    });
  }),

  http.get(`${API_BASE_URL}/drafts/:draftId`, ({ params }) =>
    params['draftId'] === DRAFT_ID
      ? HttpResponse.json({
          ...draftOf(PRODUCTS[0]),
          subscriber: SUBSCRIBER,
          simulation: SIMULATION,
          status: 'SIMULATED',
        } satisfies DraftView)
      : notFound('Unknown draft id.'),
  ),

  http.put(`${API_BASE_URL}/drafts/:draftId/subscriber`, async ({ request }) =>
    HttpResponse.json({
      ...draftOf(PRODUCTS[0]),
      subscriber: (await request.json()) as SubscriberInput,
      status: 'SUBSCRIBER_CAPTURED',
    } satisfies DraftView),
  ),

  http.put(`${API_BASE_URL}/drafts/:draftId/simulation`, () =>
    HttpResponse.json({
      ...draftOf(PRODUCTS[0]),
      subscriber: SUBSCRIBER,
      simulation: SIMULATION,
      status: 'SIMULATED',
    } satisfies DraftView),
  ),

  http.post(`${API_BASE_URL}/drafts/:draftId/confirmation`, () =>
    HttpResponse.json(confirmation(), {
      status: 201,
      headers: { Location: `${API_BASE_URL}/subscriptions/${REFERENCE}` },
    }),
  ),

  http.get(`${API_BASE_URL}/subscriptions/:reference`, ({ params }) =>
    params['reference'] === REFERENCE
      ? HttpResponse.json(confirmation())
      : notFound('Unknown subscription reference.'),
  ),
];

function confirmation(): SubscriptionConfirmation {
  return {
    reference: REFERENCE,
    product: summaryOf(PRODUCTS[0]),
    subscriber: SUBSCRIBER,
    simulation: SIMULATION,
    createdAt: '2024-05-17T09:30:00Z',
  };
}
