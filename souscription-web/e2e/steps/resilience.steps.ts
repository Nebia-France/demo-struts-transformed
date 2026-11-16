import { Given, Then, When } from '@cucumber/cucumber';
import type { Request } from '@playwright/test';
import { spawn } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createServer, request as httpRequest } from 'node:http';
import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { ROOT, VALID_SUBSCRIBER, waitForServer } from './world';
import type { SouscriptionWorld } from './world';

// Contrat de resilience (contracts/resilience-contract.json) rejoue dans le navigateur :
// timeout client de 10 s, 0 tentative de rejeu, 6 appels concurrents au maximum. La latence de la
// passerelle est injectee sur la route du navigateur, devant la doublure de passerelle ; le journal
// d'acces est celui de cette route.

const CLIENT_TIMEOUT_MS = 10_000;

interface FaultInjection {
  delaySeconds: number;
  path?: string;
  /** Requests the gateway received for `path` (its access log). */
  accessLog: { method: string; url: string }[];
  started?: number;
  failed?: { at: number; errorText: string };
  answered?: boolean;
}

const faults = new WeakMap<SouscriptionWorld, FaultInjection>();

function fault(world: SouscriptionWorld): FaultInjection {
  const injection = faults.get(world);
  assert.ok(injection, "Aucune latence n'a ete injectee sur la passerelle.");
  return injection;
}

/** SPA screen whose loading issues the gateway call `path`. */
function screenIssuing(path: string): string {
  if (path === '/api/v1/products') return '/catalogue';
  const product = /^\/api\/v1\/products\/([^/]+)$/.exec(path);
  if (product) return `/produit/${product[1]}`;
  throw new Error(`Aucun ecran du SPA n'emet ${path} a son chargement.`);
}

Given('la passerelle retarde la reponse de {int} secondes', function (this: SouscriptionWorld, seconds: number) {
  assert.ok(seconds * 1000 > CLIENT_TIMEOUT_MS, 'La latence injectee doit depasser le timeout client.');
  faults.set(this, { delaySeconds: seconds, accessLog: [] });
});

When("l'utilisateur soumet une requete vers {string}", async function (this: SouscriptionWorld, path: string) {
  const injection = fault(this);
  injection.path = path;
  const matches = (request: Request) => new URL(request.url()).pathname === path;
  // Registered after the gateway double, so it sees the call first, then hands it over late.
  await this.page.route(`**${path}`, async (route) => {
    if (!matches(route.request())) return route.fallback();
    injection.accessLog.push({ method: route.request().method(), url: route.request().url() });
    await new Promise((resolve) => setTimeout(resolve, injection.delaySeconds * 1000));
    await route.fallback().catch(() => undefined); // the browser already aborted the call
  });
  this.page.on('request', (request) => {
    if (matches(request) && injection.started === undefined) injection.started = Date.now();
  });
  this.page.on('requestfailed', (request) => {
    if (matches(request) && !injection.failed) {
      injection.failed = { at: Date.now(), errorText: request.failure()?.errorText ?? '' };
    }
  });
  this.page.on('requestfinished', (request) => {
    if (matches(request)) injection.answered = true;
  });
  await this.page.goto(new URL(screenIssuing(path), this.baseURL).toString(), { waitUntil: 'commit' });
});

Then("la requete se termine par une erreur de delai d'attente au bout de 10 secondes", async function (this: SouscriptionWorld) {
  const injection = fault(this);
  const deadline = Date.now() + CLIENT_TIMEOUT_MS + 10_000;
  while (!injection.failed && Date.now() < deadline) {
    await this.page.waitForTimeout(100);
  }
  assert.ok(injection.started, `La requete vers ${injection.path} n'a pas ete emise.`);
  assert.ok(injection.failed, `La requete vers ${injection.path} n'a pas ete interrompue par le client.`);
  assert.equal(injection.answered, undefined, 'La requete ne doit pas aboutir.');
  const elapsed = injection.failed.at - injection.started;
  assert.ok(
    elapsed >= CLIENT_TIMEOUT_MS - 250 && elapsed <= CLIENT_TIMEOUT_MS + 2_000,
    `Interruption apres ${elapsed} ms, attendu ${CLIENT_TIMEOUT_MS} ms.`,
  );
});

Then("les journaux d'acces de la passerelle ne montrent qu'une seule requete", async function (this: SouscriptionWorld) {
  const injection = fault(this);
  // Leave room for a retry that must not come.
  await this.page.waitForTimeout(2_000);
  assert.deepEqual(
    injection.accessLog.map((entry) => entry.method),
    ['GET'],
    `Journal d'acces pour ${injection.path} : ${JSON.stringify(injection.accessLog)}`,
  );
});

Then('au plus 6 requetes sont en vol simultanement vers la passerelle', function (this: SouscriptionWorld) {
  assert.ok(this.maxInFlight > 0, "Aucun appel a la passerelle n'a ete observe.");
  assert.ok(this.maxInFlight <= 6, `${this.maxInFlight} appels simultanes vers la passerelle.`);
});

// Chaos : aucune donnee de parcours ne reside dans le navigateur (hors reference de brouillon) ni
// dans une session serveur du SPA, donc l'arret d'une replique du SPA en cours de parcours
// n'entraine aucune perte de parcours (N repliques sans affinite, 0 perte). Les repliques sont N
// instances de scripts/e2e-server.mjs derriere un repartiteur tourniquet sans affinite.

interface Replica {
  port: number;
  process: ChildProcess;
  hits: number;
  stopped: boolean;
}

interface Deployment {
  replicas: Replica[];
  front: Server;
  /** Replica that served the last request, and whether it was the HTML entry document. */
  lastServed?: Replica;
  stopped?: Replica;
  hitsAtStop?: number[];
}

const deployments = new WeakMap<SouscriptionWorld, Deployment>();

function deployment(world: SouscriptionWorld): Deployment {
  const deployed = deployments.get(world);
  assert.ok(deployed, "Le SPA n'est pas deploye en repliques.");
  return deployed;
}

/** Round-robin front without session affinity; a replica that refuses the connection is skipped. */
function roundRobin(deployed: Deployment): Server {
  let next = 0;
  return createServer((incoming, outgoing) => {
    const forward = (attempt: number) => {
      const live = deployed.replicas;
      if (attempt >= live.length) {
        outgoing.writeHead(502).end();
        return;
      }
      const replica = live[next++ % live.length];
      const upstream = httpRequest(
        { host: '127.0.0.1', port: replica.port, path: incoming.url, method: incoming.method, headers: incoming.headers },
        (answer) => {
          replica.hits++;
          deployed.lastServed = replica;
          outgoing.writeHead(answer.statusCode ?? 502, answer.headers);
          answer.pipe(outgoing);
        },
      );
      upstream.on('error', () => forward(attempt + 1));
      upstream.end();
    };
    forward(0);
  });
}

Given('le SPA est deploye avec {int} repliques sans affinite de session', async function (this: SouscriptionWorld, count: number) {
  assert.ok(count >= 2, 'Au moins deux repliques sont necessaires.');
  assert.ok(existsSync(join(ROOT, 'dist/souscription-web/browser/index.html')), 'Lancer npm run build.');
  const basePort = Number(process.env.E2E_REPLICA_PORT ?? 4500);
  const replicas: Replica[] = [];
  for (let i = 0; i < count; i++) {
    const port = basePort + i;
    const child = spawn(process.execPath, ['scripts/e2e-server.mjs'], {
      cwd: ROOT,
      env: { ...process.env, E2E_PORT: String(port) },
      stdio: 'ignore',
    });
    replicas.push({ port, process: child, hits: 0, stopped: false });
  }
  this.cleanups.push(() => replicas.forEach((replica) => replica.process.kill()));
  for (const replica of replicas) {
    await waitForServer(`http://127.0.0.1:${replica.port}/healthz`);
  }
  const deployed: Deployment = { replicas, front: undefined as unknown as Server };
  deployed.front = roundRobin(deployed);
  await new Promise<void>((resolve) => deployed.front.listen(0, '127.0.0.1', resolve));
  this.cleanups.push(() => new Promise<void>((resolve) => deployed.front.close(() => resolve())));
  deployments.set(this, deployed);
  const { port } = deployed.front.address() as AddressInfo;
  await this.openContext(`http://localhost:${port}`);
});

When("une replique est arretee pendant le parcours de l'utilisateur", async function (this: SouscriptionWorld) {
  const deployed = deployment(this);
  await this.captureValidSubscriber('EQUILIBRE');
  const victim = deployed.lastServed;
  assert.ok(victim, "Aucune replique n'a servi le parcours.");
  assert.ok(
    deployed.replicas.filter((replica) => replica.hits > 0).length >= 2,
    'Sans affinite, le parcours doit avoir ete servi par plusieurs repliques.',
  );
  await new Promise<void>((resolve) => {
    victim.process.once('exit', () => resolve());
    victim.process.kill();
  });
  victim.stopped = true;
  deployed.stopped = victim;
  deployed.hitsAtStop = deployed.replicas.map((replica) => replica.hits);
});

Then("le parcours reste intact car tout l'etat est cote serveur", async function (this: SouscriptionWorld) {
  const deployed = deployment(this);
  // A full reload: the document and the assets now come from the surviving replicas only.
  await this.page.reload();
  await this.settle();
  await this.expectLocation('/souscription/simulation');
  await this.press('Continuer >');
  await this.expectLocation('/souscription/recapitulatif');
  await this.settle();
  const draft = this.gateway.currentDraft();
  assert.ok(draft, 'Le brouillon serveur est introuvable.');
  assert.equal(this.gateway.drafts.size, 1, 'Le parcours ne doit pas avoir ete recommence.');
  const stopped = deployed.stopped!;
  const index = deployed.replicas.indexOf(stopped);
  assert.equal(stopped.hits, deployed.hitsAtStop![index], "La replique arretee ne doit plus rien servir.");
  const servedAfter = deployed.replicas.reduce((sum, r, i) => sum + r.hits - deployed.hitsAtStop![i], 0);
  assert.ok(servedAfter > 0, 'Les repliques restantes doivent avoir servi la suite du parcours.');
  const summary = (await this.page.locator('main#contenu').textContent()) ?? '';
  assert.ok(summary.includes(this.gateway.product('EQUILIBRE')!.name), 'Le produit du parcours est conserve.');
  assert.ok(summary.includes(VALID_SUBSCRIBER.lastName), 'Le souscripteur du parcours est conserve.');
});
