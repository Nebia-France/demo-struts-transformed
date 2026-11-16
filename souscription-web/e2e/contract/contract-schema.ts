import Ajv2020 from 'ajv/dist/2020';
import type { ValidateFunction } from 'ajv';
import addFormats from 'ajv-formats';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { parse } from 'yaml';

/**
 * JSON Schema validators compiled from the vendored consumer contract `contracts/openapi.yaml`
 * (byte-identical to the published gateway contract, `npm run contract:check`).
 *
 * The OpenAPI components point at the shared JSON Schemas (`../shared-schemas/*.json`, 2020-12,
 * each with its own `$id`). Every `#/components/schemas/...` reference of a response is rewritten
 * onto the `$id` of the shared schema it designates, so the payloads are validated against the very
 * schemas the provider publishes, not against a copy maintained here.
 */

export const CONTRACT_PATH = resolve(__dirname, '../../contracts/openapi.yaml');

type Json = Record<string, unknown>;

interface OpenApi {
  paths: Record<string, Record<string, Json>>;
  components: { schemas: Record<string, { $ref: string }>; responses: Record<string, Json> };
}

const contract = parse(readFileSync(CONTRACT_PATH, 'utf8')) as OpenApi;
const SHARED_DIR = resolve(dirname(CONTRACT_PATH), '../shared-schemas');

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);

/** `$id` of each shared schema file, keyed by its file name. */
const sharedIds = new Map<string, string>();
for (const file of readdirSync(SHARED_DIR).filter((name) => name.endsWith('.json'))) {
  const schema = JSON.parse(readFileSync(resolve(SHARED_DIR, file), 'utf8')) as Json;
  sharedIds.set(file, String(schema['$id']));
  ajv.addSchema(schema);
}

/** `#/components/schemas/Name[/pointer]` -> `<$id of the shared file>#[/pointer]`. */
function rewriteRef(ref: string): string {
  const match = /^#\/components\/schemas\/([^/]+)(\/.*)?$/.exec(ref);
  if (!match) throw new Error(`Unsupported reference in the contract: ${ref}`);
  const component = contract.components.schemas[match[1]];
  const file = /([^/]+\.json)$/.exec(component?.$ref ?? '')?.[1];
  const id = file ? sharedIds.get(file) : undefined;
  if (!id) throw new Error(`Component ${match[1]} does not resolve to a shared schema.`);
  return match[2] ? `${id}#${match[2]}` : id;
}

function rewrite(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(rewrite);
  if (node && typeof node === 'object') {
    return Object.fromEntries(
      Object.entries(node as Json).map(([key, value]) =>
        key === '$ref' && typeof value === 'string' ? [key, rewriteRef(value)] : [key, rewrite(value)],
      ),
    );
  }
  return node;
}

export interface OperationRef {
  method: string;
  path: string;
  operation: Json;
}

/** The operations the contract declares, keyed by `operationId`. */
export const OPERATIONS: Record<string, OperationRef> = {};
for (const [path, item] of Object.entries(contract.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    if (operation && typeof operation === 'object' && 'operationId' in operation) {
      OPERATIONS[String(operation['operationId'])] = { method: method.toUpperCase(), path, operation };
    }
  }
}

const validators = new Map<string, ValidateFunction>();

/** Validator of the response body that `operationId` declares for `status`. */
export function responseValidator(operationId: string, status: number): ValidateFunction {
  const key = `${operationId} ${status}`;
  const cached = validators.get(key);
  if (cached) return cached;
  const op = OPERATIONS[operationId];
  if (!op) throw new Error(`Operation ${operationId} is not declared by the contract.`);
  const responses = op.operation['responses'] as Record<string, Json>;
  let response = responses[String(status)];
  if (!response) throw new Error(`${operationId} declares no ${status} response.`);
  const ref = response['$ref'];
  if (typeof ref === 'string') {
    response = contract.components.responses[ref.replace('#/components/responses/', '')];
  }
  const content = response['content'] as Record<string, { schema: unknown }>;
  const media = content['application/json'] ?? content['application/problem+json'];
  if (!media) throw new Error(`${operationId} ${status} declares no JSON body.`);
  const validate = ajv.compile(rewrite(media.schema) as Json);
  validators.set(key, validate);
  return validate;
}

/** Human-readable Ajv errors, empty when the payload conforms. */
export function schemaErrors(operationId: string, status: number, body: unknown): string[] {
  const validate = responseValidator(operationId, status);
  return validate(body)
    ? []
    : (validate.errors ?? []).map((e) => `${e.instancePath || '/'} ${e.message ?? ''}`.trim());
}

/**
 * Monetary fields of the contract: the DECIMAL(14,2) amounts and the DECIMAL(8,6) annual rate,
 * which the contract declares as JSON strings so the BigDecimal scale survives the wire.
 */
export const MONETARY_FIELDS = ['initialAmount', 'monthlyAmount', 'contributed', 'finalCapital', 'annualRate'];

/** Path of every monetary field of `payload` that is not a JSON string. */
export function nonStringMonetaryFields(payload: unknown, path = ''): string[] {
  if (Array.isArray(payload)) {
    return payload.flatMap((item, i) => nonStringMonetaryFields(item, `${path}/${i}`));
  }
  if (payload && typeof payload === 'object') {
    return Object.entries(payload as Json).flatMap(([key, value]) => [
      ...(MONETARY_FIELDS.includes(key) && typeof value !== 'string' ? [`${path}/${key}`] : []),
      ...nonStringMonetaryFields(value, `${path}/${key}`),
    ]);
  }
  return [];
}

/** Number of monetary fields present in `payload`, so a test cannot pass on an empty body. */
export function countMonetaryFields(payload: unknown): number {
  if (Array.isArray(payload)) return payload.reduce((n: number, item) => n + countMonetaryFields(item), 0);
  if (payload && typeof payload === 'object') {
    return Object.entries(payload as Json).reduce(
      (n, [key, value]) => n + (MONETARY_FIELDS.includes(key) ? 1 : 0) + countMonetaryFields(value),
      0,
    );
  }
  return 0;
}
