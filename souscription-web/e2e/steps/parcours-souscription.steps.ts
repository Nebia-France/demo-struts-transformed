import { Given, Then, When } from '@cucumber/cucumber';
import { strict as assert } from 'node:assert';
import type { SouscriptionWorld } from './world';
import { VALID_SUBSCRIBER } from './world';

// Parcours de souscription (source : features/step_definitions/ParcoursSouscriptionSteps.java)
// Le SPA est pilote dans Chromium ; les chemins `*.do` du Gherkin designent les routes SPA qui
// les remplacent (src/app/app.routes.ts). Le brouillon vit cote serveur : son etat est lu dans la
// doublure de passerelle (e2e/support/fake-gateway.ts), sa reference dans `sessionStorage`.

/** Libelle du bouton portant chacune des deux commandes de l'etape de simulation. */
const SIMULATION_COMMANDS: Readonly<Record<string, string>> = {
  calculer: 'Recalculer la simulation',
  continuer: 'Continuer >',
};

Given("l'utilisateur a demarre le parcours pour le produit {string}", async function (this: SouscriptionWorld, code: string) {
  assert.ok(await this.startJourney(code), `Le parcours n'a pas demarre pour ${code}.`);
});
Given("l'utilisateur a renseigne un souscripteur valide pour le produit {string}", async function (this: SouscriptionWorld, code: string) {
  await this.captureValidSubscriber(code);
});
Given("aucun brouillon n'existe en session", async function (this: SouscriptionWorld) {
  // Contexte de navigateur neuf : aucune reference de brouillon, aucun brouillon serveur.
  assert.equal(this.gateway.drafts.size, 0);
  await this.open('/catalogue');
  assert.equal(await this.storedDraftRef(), null);
});
When("l'utilisateur demarre le parcours pour le produit {string}", async function (this: SouscriptionWorld, code: string) {
  if (await this.startJourney(code)) {
    return;
  }
  // Produit inconnu : la fiche n'est pas rendue (/erreur/404), le SPA n'offre donc aucun moyen
  // de demarrer le parcours. L'operation startDraft que le bouton aurait emise est rejouee depuis
  // l'origine du SPA pour constater le refus de la passerelle (ex-`commencer.do` -> 400).
  assert.equal(this.errorPageStatus(), 404, 'La fiche d\'un produit inconnu ne doit pas etre rendue.');
  const { status } = await this.fromSpaOrigin('POST', '/api/v1/drafts', { productCode: code });
  this.spaStatus = status;
});
When("l'utilisateur soumet une identification valide du souscripteur", async function (this: SouscriptionWorld) {
  await this.fillSubscriber(VALID_SUBSCRIBER);
  await this.submitSubscriber();
});
When("l'utilisateur soumet une identification invalide du souscripteur", async function (this: SouscriptionWorld) {
  // Format admis par le client, refuse par la regle serveur (adresse sans domaine de premier niveau).
  await this.fillSubscriber({ ...VALID_SUBSCRIBER, email: 'camille@example' });
  await this.submitSubscriber();
});
When("l'utilisateur soumet une simulation valide avec la commande {string}", async function (this: SouscriptionWorld, commande: string) {
  await this.fillSimulation({ initial: '1000,00', monthly: '100,00', years: '5' });
  await this.press(SIMULATION_COMMANDS[commande]);
});
When("l'utilisateur soumet une simulation invalide avec la commande {string}", async function (this: SouscriptionWorld, commande: string) {
  // Format admis par le client, hors bornes pour la regle serveur.
  await this.fillSimulation({ initial: '99,99', monthly: '100,00', years: '5' });
  await this.press(SIMULATION_COMMANDS[commande]);
});
When("l'utilisateur soumet la simulation avec la commande {string}", async function (this: SouscriptionWorld, commande: string) {
  const label = SIMULATION_COMMANDS[commande];
  if (label !== undefined) {
    await this.press(label);
    return;
  }
  // Le SPA n'expose que les deux commandes du formulaire legacy, chacune portee par son bouton :
  // aucune autre commande ne peut etre emise. La requete que le legacy refusait en 400 est donc
  // refusee avant transport : aucun appel n'atteint la passerelle et le brouillon est intact.
  const calls = this.gateway.calls.length;
  assert.equal(await this.page.getByRole('button', { name: commande, exact: true }).count(), 0);
  await this.idle();
  assert.equal(this.gateway.calls.length, calls, 'Une commande inconnue a emis un appel.');
  assert.equal(this.gateway.currentDraft()?.simulation, null);
  this.spaStatus = 400;
});
When("l'utilisateur consulte le recapitulatif", async function (this: SouscriptionWorld) {
  await this.open('/souscription/recapitulatif');
});
When("l'utilisateur consulte l'etape de simulation", async function (this: SouscriptionWorld) {
  await this.open('/souscription/simulation');
});
When("l'utilisateur revient sur l'etape d'identification du souscripteur", async function (this: SouscriptionWorld) {
  await this.open('/souscription/souscripteur');
});
When("l'utilisateur revient sur l'etape de simulation", async function (this: SouscriptionWorld) {
  await this.open('/souscription/simulation');
});
When("l'utilisateur appelle un chemin inconnu du parcours", async function (this: SouscriptionWorld) {
  await this.open('/souscription/chemin-inconnu');
});
When("l'utilisateur modifie l'identification du souscripteur avec des donnees valides", async function (this: SouscriptionWorld) {
  this.previousKey = this.gateway.currentDraft()?.confirmationKey;
  await this.open('/souscription/souscripteur');
  await this.fillSubscriber({ firstName: 'Dominique' });
  await this.submitSubscriber();
  await this.expectLocation('/souscription/simulation');
});
Then("un brouillon est cree en session pour le produit {string}", async function (this: SouscriptionWorld, code: string) {
  const draft = this.gateway.currentDraft();
  assert.equal(draft?.product.code, code);
  const stored = JSON.parse((await this.storedDraftRef()) ?? 'null') as { draftId?: string } | null;
  assert.equal(stored?.draftId, draft?.draftId, 'La reference du brouillon n\'est pas conservee par le SPA.');
  await this.page.locator('p.intro strong').filter({ hasText: draft!.product.name }).waitFor();
});
Then("le brouillon contient une simulation calculee", async function (this: SouscriptionWorld) {
  // `calculer` est l'operation sans effet de bord POST /simulations : la projection calculee
  // par la passerelle est portee par l'etape et affichee sans quitter l'etape.
  const call = this.gateway.lastCall('simulate');
  assert.equal(call?.status, 200, 'Aucune simulation calculee par la passerelle.');
  const capital = (await this.page.locator('#capital-final').textContent()) ?? '';
  assert.ok(/\d/.test(capital), 'Le resultat de la simulation n\'est pas affiche.');
});
Then("le formulaire est prerempli avec les donnees du souscripteur enregistre", async function (this: SouscriptionWorld) {
  const subscriber = this.gateway.currentDraft()?.subscriber;
  assert.ok(subscriber, 'Aucun souscripteur enregistre.');
  const [year, month, day] = subscriber.birthDate.split('-');
  await this.page.locator('input#firstName').waitFor();
  assert.equal(await this.page.inputValue('input#firstName'), subscriber.firstName);
  assert.equal(await this.page.inputValue('input#lastName'), subscriber.lastName);
  assert.equal(await this.page.inputValue('input#email'), subscriber.email);
  assert.equal(await this.page.inputValue('input#birthDate'), `${day}/${month}/${year}`);
});
Then("le formulaire est prerempli avec les montants de la simulation enregistree", async function (this: SouscriptionWorld) {
  const simulation = this.gateway.currentDraft()?.simulation;
  assert.ok(simulation, 'Aucune simulation enregistree.');
  await this.page.locator('input#initial').waitFor();
  assert.equal(await this.page.inputValue('input#initial'), simulation.initialAmount.replace('.', ','));
  assert.equal(await this.page.inputValue('input#monthly'), simulation.monthlyAmount.replace('.', ','));
  assert.equal(await this.page.inputValue('input#years'), String(simulation.durationYears));
});
Then("le systeme reaffiche l'etape d'identification avec les erreurs de validation", async function (this: SouscriptionWorld) {
  await this.expectLocation('/souscription/souscripteur');
  await this.page.locator('#erreurs').waitFor();
  assert.ok(await this.fieldInError('email'));
});
Then("le systeme reaffiche l'etape de simulation avec les erreurs de validation", async function (this: SouscriptionWorld) {
  await this.expectLocation('/souscription/simulation');
  await this.page.locator('#erreurs').waitFor();
  assert.ok(await this.fieldInError('initial'));
});
Then("aucun souscripteur n'est enregistre dans le brouillon", function (this: SouscriptionWorld) {
  assert.equal(this.gateway.currentDraft()?.subscriber, null);
});
Then("aucune simulation n'est enregistree dans le brouillon", function (this: SouscriptionWorld) {
  assert.equal(this.gateway.currentDraft()?.simulation, null);
});
Then("la cle de confirmation du brouillon est renouvelee", async function (this: SouscriptionWorld) {
  const draft = this.gateway.currentDraft();
  assert.ok(this.previousKey && draft);
  assert.notEqual(draft.confirmationKey, this.previousKey, 'La cle de confirmation n\'a pas ete renouvelee.');
  // Le SPA adopte la cle renouvelee : la confirmation emise ensuite la porte et aboutit.
  await this.open('/souscription/recapitulatif');
  await this.page.locator('input#accepted').check();
  await this.press('Confirmer la souscription >');
  assert.equal(this.gateway.lastCall('confirmDraft')?.status, 201);
  assert.deepEqual(this.gateway.lastCall('confirmDraft')?.requestBody, { confirmationKey: draft.confirmationKey });
});

// Etapes transverses partagees par les features du SPA (source : features/step_definitions/CommonSteps.java)
Given("l'application Horizon est demarree", async function (this: SouscriptionWorld) {
  const { status } = await this.call('/', 'GET');
  if (status >= 400) {
    throw new Error(
      `L'artefact statique n'est pas servi sur ${this.baseURL} (statut ${status}). ` +
        'Lancer le conteneur et definir E2E_BASE_URL.',
    );
  }
});
Given("l'utilisateur dispose d'une session valide", async function (this: SouscriptionWorld) {
  // La « session » est le cookie double-submit emis par la passerelle au demarrage du SPA.
  await this.open('/catalogue');
  assert.ok(await this.csrfCookie(), 'Aucun jeton anti-CSRF emis au demarrage du SPA.');
});
Given("l'utilisateur a enregistre une simulation pour le produit {string}", async function (this: SouscriptionWorld, code: string) {
  await this.recordSimulation(code);
});
When("l'utilisateur appelle {string} en methode {string}", async function (this: SouscriptionWorld, path: string, method: string) {
  await this.call(path, method);
  // Une consultation GET d'une action du parcours est aussi l'ouverture de la route SPA qui la
  // remplace : le SPA demarre, obtient son jeton et rend l'ecran.
  if (method === 'GET' && this.isLegacyRoute(path)) {
    await this.open(this.spaRoute(path));
  }
});
Then("le systeme repond avec le code HTTP {int}", async function (this: SouscriptionWorld, code: number) {
  await this.idle();
  const status = this.observedStatus();
  assert.equal(status, code, `Code HTTP attendu ${code}, obtenu ${status}.`);
});
Then("le systeme redirige l'utilisateur vers {string}", async function (this: SouscriptionWorld, legacy: string) {
  await this.expectLocation(this.spaRoute(legacy));
});
Then("le titre de la page est {string}", async function (this: SouscriptionWorld, title: string) {
  await this.page.locator('main#contenu h1').filter({ hasText: title }).waitFor();
  assert.equal(((await this.page.locator('main#contenu h1').textContent()) ?? '').trim(), title);
  assert.equal(await this.page.title(), `${title} | Financière Horizon`);
});
Then("le systeme indique l'etape {int} du parcours", async function (this: SouscriptionWorld, step: number) {
  await this.page.locator('ol.steps > li.current').waitFor();
  assert.equal(await this.currentStep(), step);
});
Then("une erreur {string} est levee", function (this: SouscriptionWorld, message: string) {
  assert.equal(this.raisedError, message, `Erreur attendue « ${message} », obtenue « ${this.raisedError ?? 'aucune'} ».`);
});
When("le systeme recherche une souscription avec la cle {string}", async function (this: SouscriptionWorld, cle: string) {
  await this.open('/catalogue');
  const { status } = await this.fromSpaOrigin('GET', `/api/v1/subscriptions/${encodeURIComponent(cle)}`);
  this.spaStatus = status;
});
Then("aucune souscription n'est retournee", function (this: SouscriptionWorld) {
  assert.equal(this.spaStatus, 404);
});
Then("la souscription existante est retournee sans nouvel enregistrement", async function (this: SouscriptionWorld) {
  assert.equal(this.gateway.subscriptions.length, 1, 'Un second enregistrement a ete cree.');
  const existing = this.gateway.subscriptions[0];
  await this.expectLocation('/souscription/confirmation');
  assert.equal(((await this.page.locator('#reference').textContent()) ?? '').trim(), existing.reference);
});
Then("le champ {string} est en erreur : {word}", async function (this: SouscriptionWorld, champ: string, attendu: string) {
  await this.flush();
  assert.equal(await this.fieldInError(champ), attendu === 'oui', `Champ ${champ} : en erreur attendu « ${attendu} ».`);
});
