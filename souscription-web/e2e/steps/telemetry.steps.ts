import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { VALID_SUBSCRIBER } from './world';
import type { SouscriptionWorld } from './world';

// W7-T04 : verification de bout en bout du puits de telemetrie (plan 36). Une erreur injectee
// doit apparaitre dans le collecteur avec la route et la version deployee, et aucune charge utile
// ne doit transporter de donnee personnelle ni le jeton CSRF. Le collecteur est la route
// `/telemetry/**` du navigateur : il recoit les balises (`navigator.sendBeacon`) emises par le SPA.

interface Payload {
  kind: string;
  name: string;
  value?: number;
  route: string;
  release: string;
  traceId?: string;
}

interface Collector {
  received: Payload[];
  raw: string[];
  injectedOn?: string;
  csrfToken?: string;
}

const collectors = new WeakMap<SouscriptionWorld, Collector>();

function collector(world: SouscriptionWorld): Collector {
  const found = collectors.get(world);
  assert.ok(found, "Aucun collecteur de telemetrie n'est joignable.");
  return found;
}

async function waitFor(world: SouscriptionWorld, predicate: () => boolean, timeout = 5_000): Promise<void> {
  const deadline = Date.now() + timeout;
  while (!predicate() && Date.now() < deadline) {
    await world.page.waitForTimeout(50);
  }
}

Given('un collecteur de telemetrie est joignable', async function (this: SouscriptionWorld) {
  const found: Collector = { received: [], raw: [] };
  collectors.set(this, found);
  // Registered after the default 204 route of the world, so it receives the beacons first.
  await this.page.route('**/telemetry/**', async (route) => {
    const body = route.request().postData() ?? '';
    found.raw.push(body);
    found.received.push(JSON.parse(body) as Payload);
    await route.fulfill({ status: 204 });
  });
});

When('une erreur non interceptee est injectee sur la route {string}', async function (this: SouscriptionWorld, route: string) {
  const found = collector(this);
  if (route === '/souscription/simulation') {
    // The subscriber is identified, so personal data and the CSRF token are in the page.
    await this.captureValidSubscriber('EQUILIBRE');
  } else {
    await this.open(route);
  }
  await this.expectLocation(route);
  found.csrfToken = await this.csrfCookie();
  found.received.length = 0;
  found.raw.length = 0;
  found.injectedOn = route;
  // A rejection nobody handles, whose message carries an address and a serialized request body
  // with the subscriber fields and the CSRF token, as a failing HTTP library may produce.
  await this.page.evaluate(
    ({ subscriber, token }) => {
      setTimeout(() => {
        const body = JSON.stringify({ ...subscriber, csrf: token });
        void Promise.reject(new Error(`echec de l'appel pour <${subscriber.email}> ${body}`));
      });
    },
    { subscriber: VALID_SUBSCRIBER, token: found.csrfToken ?? '' },
  );
  await waitFor(this, () => found.received.length > 0);
  // Leave room for a duplicate report that must not come.
  await this.page.waitForTimeout(1_000);
});

Then('le collecteur recoit exactement une charge utile', function (this: SouscriptionWorld) {
  const found = collector(this);
  // One error report per uncaught error. The navigation of GlobalErrorHandler to /erreur/500
  // also emits its own journey event, a different kind of signal.
  const errors = found.received.filter((payload) => payload.kind === 'error');
  assert.equal(errors.length, 1, `Charges utiles recues : ${JSON.stringify(found.received)}`);
});

Then('la charge utile porte la route et la version deployee', async function (this: SouscriptionWorld) {
  const found = collector(this);
  const payload = found.received.find((p) => p.kind === 'error')!;
  assert.equal(payload.route, found.injectedOn);
  const version = (await this.page.evaluate(async () => {
    const response = await fetch('/version.json', { cache: 'no-store' });
    return (await response.json()) as { release: string };
  })).release;
  assert.ok(version, 'version.json porte une version.');
  assert.equal(payload.release, version);
});

Then('la charge utile ne porte aucune donnee personnelle ni jeton CSRF', function (this: SouscriptionWorld) {
  const found = collector(this);
  const forbidden = [
    VALID_SUBSCRIBER.firstName,
    VALID_SUBSCRIBER.lastName,
    VALID_SUBSCRIBER.email,
    VALID_SUBSCRIBER.birthDate,
    'lastName',
  ];
  if (found.csrfToken) forbidden.push(found.csrfToken);
  for (const body of found.raw) {
    for (const value of forbidden) {
      assert.ok(!body.includes(value), `La charge utile contient « ${value} » : ${body}`);
    }
    assert.ok(!body.includes('@'), `La charge utile contient une adresse : ${body}`);
  }
});

Then('le collecteur recoit les metriques LCP, INP et CLS de la route mesuree', async function (this: SouscriptionWorld) {
  const found = collector(this);
  await this.open('/catalogue');
  // One interaction, so the INP observer has an event to measure.
  await this.page.locator('main#contenu h1').first().click();
  found.received.length = 0;
  // The document becomes hidden: the SPA flushes its vitals, as on a tab switch.
  await this.page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    // As the browser does, the event bubbles from the document to the window.
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
  });
  await waitFor(this, () => found.received.filter((p) => p.kind === 'vital').length >= 3);
  const vitals = found.received.filter((p) => p.kind === 'vital');
  assert.deepEqual(vitals.map((p) => p.name).sort(), ['CLS', 'INP', 'LCP']);
  for (const vital of vitals) {
    assert.equal(vital.route, '/catalogue');
    assert.equal(typeof vital.value, 'number');
  }
  assert.ok(vitals.find((p) => p.name === 'LCP')!.value! > 0, 'Le LCP de /catalogue est mesure.');
});
