import { Given, Then, When } from '@cucumber/cucumber';
import { strict as assert } from 'node:assert';
import { CSRF_HEADER } from '../support/fake-gateway';
import type { SouscriptionWorld } from './world';
import { VALID_SUBSCRIBER } from './world';

// Securite web (source : features/step_definitions/SecuriteWebSteps.java)
// Le jeton de session verifie par SecurityFilter.java est remplace par le double-submit
// cookie XSRF-TOKEN / entete X-CSRF-Token (contracts/security-policy.json) : rejet 403 sur
// POST/PUT/PATCH/DELETE. La « session » est le contexte du navigateur : le jeton y est le
// cookie que la passerelle emet au demarrage du SPA et que l'intercepteur XSRF d'Angular renvoie
// en entete. Les entetes de reponse sont ceux de l'hote statique (deploy/nginx.conf).

/**
 * Soumission de l'etape souscripteur (ex-`/souscripteur.do`) par le SPA. `header` remplace
 * l'entete X-CSRF-Token que l'intercepteur a pose ; `null` le retire.
 */
async function submitSubscriber(world: SouscriptionWorld, header: string | null | undefined): Promise<void> {
  assert.ok(await world.startJourney('PRUDENT'));
  if (header !== undefined) {
    await world.page.route('**/api/v1/drafts/*/subscriber', async (route) => {
      const headers = { ...(await route.request().allHeaders()) };
      delete headers[CSRF_HEADER];
      if (header !== null) headers[CSRF_HEADER] = header;
      await route.fallback({ headers });
    });
  }
  await world.fillSubscriber(VALID_SUBSCRIBER);
  await world.submitSubscriber();
}

Given("la session de l'utilisateur ne contient aucun jeton anti-CSRF", async function (this: SouscriptionWorld) {
  assert.equal(await this.csrfCookie(), undefined);
});
Given("la session de l'utilisateur contient le jeton anti-CSRF {string}", async function (this: SouscriptionWorld, jeton: string) {
  await this.setCsrfCookie(jeton);
});
When("l'utilisateur soumet {string} avec le jeton anti-CSRF {string}", async function (this: SouscriptionWorld, action: string, jeton: string) {
  assert.equal(this.spaRoute(action), '/souscription/souscripteur');
  const session = await this.csrfCookie();
  await submitSubscriber(this, jeton === session ? undefined : jeton);
});
When("l'utilisateur soumet {string} sans jeton anti-CSRF", async function (this: SouscriptionWorld, action: string) {
  assert.equal(this.spaRoute(action), '/souscription/souscripteur');
  await submitSubscriber(this, null);
});
When("l'utilisateur soumet {string} avec un jeton anti-CSRF de {int} caracteres", async function (this: SouscriptionWorld, action: string, longueur: number) {
  assert.equal(this.spaRoute(action), '/souscription/souscripteur');
  const token = 'a'.repeat(longueur);
  // Cookie et entete concordants : seule la longueur du jeton est en cause.
  await this.setCsrfCookie(token);
  await submitSubscriber(this, token);
});
Then("la reponse porte l'entete {string} avec la valeur {string}", function (this: SouscriptionWorld, a0: string, a1: string) {
  const { headers } = this.requireLastResponse();
  const actual = headers[a0.toLowerCase()];
  assert.equal(actual, a1, `Entete ${a0} : attendu "${a1}", obtenu "${actual ?? '(absente)'}"`);
});
Then("la reponse porte une politique de securite du contenu restrictive", function (this: SouscriptionWorld) {
  const { headers } = this.requireLastResponse();
  const csp = headers['content-security-policy'];
  assert.ok(csp, 'Entete Content-Security-Policy absente de la reponse servie.');
  // Equivalent ou plus strict que SecurityFilter.java ; script-src passe de 'none' a 'self'
  // car le SPA execute ses propres bundles hashes.
  for (const directive of [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
    "form-action 'self'",
  ]) {
    assert.ok(csp.includes(directive), `Directive CSP manquante : ${directive} (CSP : ${csp})`);
  }
  assert.ok(!/unsafe-inline|unsafe-eval/i.test(csp), `CSP affaiblie par unsafe-* : ${csp}`);
  assert.ok(!/(^|[\s'"])\*/.test(csp), `CSP affaiblie par une source joker : ${csp}`);
});
// L'encodage UTF-8 des requetes et des reponses (ex-SecurityFilter.setCharacterEncoding) est
// applique par souscription-gateway (TARGET_APPLICATION_TOPOLOGY : securityFilter ->
// souscription-gateway) ; le scenario est alloue a ce tier et rejoue contre elle (E2E_BASE_URL).
Then("l'encodage de la requete est {string}", async function (this: SouscriptionWorld, charset: string) {
  const { path, method } = this.lastRequest!;
  const { status } = await this.call(`${path}${path.includes('?') ? '&' : '?'}q=%C3%A9t%C3%A9`, method, {
    'Content-Type': `application/x-www-form-urlencoded; charset=${charset}`,
  });
  assert.ok(status < 400, `Requete encodee en ${charset} refusee (statut ${status}).`);
});
Then("l'encodage de la reponse est {string}", function (this: SouscriptionWorld, charset: string) {
  const type = this.requireLastResponse().headers['content-type'] ?? '';
  const declared = /charset=([^;\s]+)/i.exec(type)?.[1];
  assert.equal(declared?.toUpperCase(), charset.toUpperCase(), `Content-Type sans charset ${charset} : « ${type} »`);
});
Then("un jeton anti-CSRF est present en session", async function (this: SouscriptionWorld) {
  assert.ok(this.gateway.callsOf('issueCsrfToken').length > 0, "Le SPA n'a pas demande de jeton au demarrage.");
  const token = await this.csrfCookie();
  assert.ok(token && token.length > 0, "Aucun jeton anti-CSRF n'est present dans le navigateur.");
});
Then("le jeton anti-CSRF en session reste {string}", async function (this: SouscriptionWorld, jeton: string) {
  assert.ok(this.gateway.callsOf('issueCsrfToken').length > 0, "Le SPA n'a pas demande de jeton au demarrage.");
  assert.equal(await this.csrfCookie(), jeton);
});
Then("la requete est transmise a l'action du parcours", async function (this: SouscriptionWorld) {
  assert.equal(this.gateway.lastCall('putDraftSubscriber')?.status, 200, 'La soumission portant le jeton attendu a ete rejetee.');
  await this.expectLocation('/souscription/simulation');
});
Then("la requete n'est pas transmise a l'action du parcours", function (this: SouscriptionWorld) {
  const status = this.gateway.lastCall('putDraftSubscriber')?.status;
  assert.equal(status, 403, `La soumission erronee aurait du etre rejetee en 403 (obtenu ${status}).`);
  assert.equal(this.gateway.currentDraft()?.subscriber, null);
});
Then("la requete est transmise a la chaine de traitement", function (this: SouscriptionWorld) {
  // La requete a traverse la chaine statique : le serveur a repondu lui-meme, sans rejet de
  // securite. Le chemin herite `/assets/css/application.css` n'existe plus dans le SPA (bundles
  // hashes) et retombe sur index.html ; seuls un rejet (403/405) ou une absence de reponse
  // constituent une regression.
  const { status } = this.requireLastResponse();
  assert.ok(
    status !== 403 && status !== 405 && status < 500,
    `La ressource statique n'a pas traverse la chaine de traitement (statut ${status}).`,
  );
});
Then("aucune verification de jeton anti-CSRF n'est effectuee", function (this: SouscriptionWorld) {
  const { status } = this.requireLastResponse();
  assert.notEqual(status, 403, 'Une ressource statique a ete soumise a la verification anti-CSRF.');
});
Then("le systeme ne repond pas avec le code HTTP {int}", function (this: SouscriptionWorld, a0: number) {
  const { status } = this.requireLastResponse();
  assert.notEqual(status, a0, `Le systeme a repondu ${a0} alors que ce code est exclu.`);
});
