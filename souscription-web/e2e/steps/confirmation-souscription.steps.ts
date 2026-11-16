import { Given, Then, When } from '@cucumber/cucumber';
import { strict as assert } from 'node:assert';
import { calculate } from '../support/fake-gateway';
import type { SouscriptionWorld } from './world';

// Confirmation de souscription (source : features/step_definitions/ConfirmationSouscriptionSteps.java)
// Le recapitulatif (etape 4) du SPA emet POST /drafts/{id}/confirmation avec la cle de
// confirmation du brouillon ; l'enregistrement est lu dans la doublure de passerelle.

const CONFIRM = 'Confirmer la souscription >';

async function textOf(world: SouscriptionWorld, selector: string): Promise<string> {
  return ((await world.page.locator(selector).first().textContent()) ?? '').replace(/\s+/g, ' ').trim();
}

/** Clique la confirmation et attend la reponse de la passerelle et le rendu qui suit. */
async function confirm(world: SouscriptionWorld): Promise<void> {
  await world.press(CONFIRM);
}

Given("l'utilisateur a accepte les informations et les hypotheses de simulation", async function (this: SouscriptionWorld) {
  await this.page.locator('input#accepted').check();
});
Given("l'utilisateur n'a pas accepte les informations et les hypotheses de simulation", async function (this: SouscriptionWorld) {
  await this.page.locator('input#accepted').uncheck();
});
Given("la souscription de l'utilisateur a ete enregistree", async function (this: SouscriptionWorld) {
  await confirm(this);
  await this.expectLocation('/souscription/confirmation');
  assert.equal(this.gateway.subscriptions.length, 1);
});
Given("une souscription existe deja pour la cle de confirmation du brouillon", function (this: SouscriptionWorld) {
  this.gateway.seedSubscription(this.gateway.currentDraft()!);
});
Given("l'enregistrement des souscriptions est momentanement indisponible", function (this: SouscriptionWorld) {
  this.gateway.confirmUnavailable = true;
});
Given("le brouillon ne contient pas de souscripteur", function (this: SouscriptionWorld) {
  this.gateway.currentDraft()!.subscriber = null;
});
Given("le brouillon ne contient pas de simulation", function (this: SouscriptionWorld) {
  this.gateway.currentDraft()!.simulation = null;
});
Given("aucune souscription n'a ete enregistree pour la session", async function (this: SouscriptionWorld) {
  assert.equal(this.gateway.subscriptions.length, 0);
  // Session neuve : le SPA ne detient aucune souscription aboutie.
  await this.context.clearCookies();
  await this.page.evaluate(() => sessionStorage.clear());
});
When("l'utilisateur confirme la demande avec la cle de confirmation du brouillon", async function (this: SouscriptionWorld) {
  await confirm(this);
});
When("l'utilisateur confirme la demande avec une cle de confirmation erronee", async function (this: SouscriptionWorld) {
  // La cle detenue par le SPA ne correspond plus au brouillon serveur (modification concurrente).
  this.gateway.rotateKeyBehindClient(this.gateway.currentDraft()!);
  await confirm(this);
});
When("l'utilisateur soumet a nouveau la confirmation avec la meme cle", async function (this: SouscriptionWorld) {
  // Re-soumission de la meme requete (double clic, rejeu du navigateur) depuis l'origine du SPA.
  const first = this.gateway.lastCall('confirmDraft');
  assert.ok(first, 'Aucune confirmation emise.');
  const { status, body } = await this.fromSpaOrigin('POST', `/api/v1${first.path}`, first.requestBody);
  assert.equal(status, 200, 'Le rejeu de la meme cle doit renvoyer la souscription existante.');
  assert.equal((body as { reference?: string }).reference, this.gateway.subscriptions[0]?.reference);
});
When("l'utilisateur consulte la page de confirmation", async function (this: SouscriptionWorld) {
  // Apres confirmation le SPA affiche deja la page ; sinon, l'URL est saisie.
  if (new URL(this.page.url()).pathname !== '/souscription/confirmation') {
    await this.open('/souscription/confirmation');
  }
});
When("le systeme tente de confirmer le brouillon", async function (this: SouscriptionWorld) {
  // Le recapitulatif est recharge depuis le brouillon serveur : le parcours incomplet est
  // detecte par les gardes de sequencement, qui ramenent a l'etape manquante sans confirmation.
  await this.open('/souscription/recapitulatif');
  const path = new URL(this.page.url()).pathname;
  if (path === '/souscription/recapitulatif') {
    await this.page.locator('input#accepted').check();
    await confirm(this);
  }
  const accepted = this.gateway.callsOf('confirmDraft').some((c) => c.status < 300);
  const draft = this.gateway.currentDraft()!;
  const sentBack =
    (!draft.subscriber && this.location() === '/souscription/souscripteur') ||
    (!draft.simulation && this.location() === '/souscription/simulation') ||
    this.gateway.lastCall('confirmDraft')?.status === 409;
  if (!accepted && sentBack && this.gateway.subscriptions.length === 0) {
    this.raisedError = 'Parcours incomplet';
  }
});
When("le systeme enregistre la souscription pour cette cle", async function (this: SouscriptionWorld) {
  await confirm(this);
});
When("le systeme recherche la souscription avec la cle de confirmation utilisee", async function (this: SouscriptionWorld) {
  const reference = await textOf(this, '#reference');
  const { status, body } = await this.fromSpaOrigin('GET', `/api/v1/subscriptions/${encodeURIComponent(reference)}`);
  this.spaStatus = status;
  this.lookedUp = body as Record<string, unknown>;
});
Then("la souscription est enregistree avec une reference unique", async function (this: SouscriptionWorld) {
  assert.equal(this.gateway.subscriptions.length, 1);
  const { reference } = this.gateway.subscriptions[0];
  await this.page.locator('#reference').waitFor();
  assert.equal(await textOf(this, '#reference'), reference);
});
Then("le brouillon est retire de la session", async function (this: SouscriptionWorld) {
  assert.equal(await this.storedDraftRef(), null, 'La reference du brouillon est restee en session.');
});
Then("la reference de la souscription est affichee", async function (this: SouscriptionWorld) {
  assert.equal(await textOf(this, '#reference'), this.gateway.subscriptions[0].reference);
});
Then("la date de creation de la souscription est affichee au format {string}", async function (this: SouscriptionWorld, format: string) {
  const pattern = new RegExp(
    format.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/JJ|MM|HH|mm/g, '\\d{2}').replace('AAAA', '\\d{4}'),
  );
  const row = this.page.locator('table.details tr').filter({ hasText: 'Enregistrée le' });
  const shown = ((await row.locator('td').textContent()) ?? '').trim();
  assert.match(shown, pattern, `Date de creation « ${shown} » hors format ${format}.`);
});
Then("la simulation enregistree est recalculee avec le taux annuel du produit", function (this: SouscriptionWorld) {
  const [record] = this.gateway.subscriptions;
  const recomputed = calculate(
    record.simulation.initialAmount,
    record.simulation.monthlyAmount,
    record.simulation.durationYears,
    record.product.annualRate,
  );
  assert.deepEqual(record.simulation, recomputed);
});
Then("le capital final enregistre correspond au capital recalcule", async function (this: SouscriptionWorld) {
  const [record] = this.gateway.subscriptions;
  const shown = (await textOf(this, '#capital-final')).replace(/[\s\u00A0\u202F€]/g, '').replace(',', '.');
  assert.equal(shown, record.simulation.finalCapital);
});
Then("une seule souscription est enregistree", function (this: SouscriptionWorld) {
  assert.equal(this.gateway.subscriptions.length, 1);
});
Then("aucune souscription n'est enregistree", function (this: SouscriptionWorld) {
  assert.equal(this.gateway.subscriptions.length, 0);
});
Then("le systeme reaffiche le recapitulatif avec l'erreur sur le champ {string}", async function (this: SouscriptionWorld, champ: string) {
  await this.expectLocation('/souscription/recapitulatif');
  const message = this.page.locator(`#${champ}-error`);
  await message.waitFor();
  assert.notEqual(((await message.textContent()) ?? '').trim(), '', `Aucun message d'erreur pour ${champ}.`);
});
Then("la saisie de l'utilisateur est conservee", async function (this: SouscriptionWorld) {
  assert.ok(await this.page.locator('input#accepted').isChecked(), "L'acceptation n'a pas ete conservee.");
  const subscriber = this.gateway.currentDraft()!.subscriber!;
  const row = this.page.locator('table.details tr').filter({ hasText: 'Souscripteur' });
  assert.equal(((await row.locator('td').textContent()) ?? '').trim(), `${subscriber.firstName} ${subscriber.lastName}`);
});
Then("la souscription retournee porte les donnees du souscripteur et de la simulation", function (this: SouscriptionWorld) {
  assert.equal(this.spaStatus, 200);
  const [record] = this.gateway.subscriptions;
  assert.deepEqual(this.lookedUp?.['subscriber'], record.subscriber);
  assert.deepEqual(this.lookedUp?.['simulation'], record.simulation);
});
