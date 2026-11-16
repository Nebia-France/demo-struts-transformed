import { Given, Then, When } from '@cucumber/cucumber';
import { strict as assert } from 'node:assert';
import { toPercent } from '../../src/app/core/locale/rate';
import { REFERENCE_PRODUCTS } from '../support/fake-gateway';
import type { SouscriptionWorld } from './world';

// Catalogue des placements (source : features/step_definitions/CatalogueSteps.java)
// Le catalogue est lu par le SPA (liste /catalogue, fiche /produit/:code) aupres de la doublure
// de passerelle chargee avec les produits de reference de CatalogService.

/** Cellule de la ligne `label` du tableau de la fiche produit. */
async function detailRow(world: SouscriptionWorld, label: string): Promise<string> {
  const row = world.page.locator('table.details tr').filter({ has: world.page.locator('th', { hasText: label }) });
  return ((await row.locator('td').textContent()) ?? '').replace(/\s+/g, ' ').trim();
}

/** Pourcentage affiche (`6 %`) en nombre. */
function displayedPercent(text: string): number {
  const match = /(-?\d+(?:,\d+)?)\s*%/.exec(text);
  assert.ok(match, `Aucun pourcentage affiche : « ${text} »`);
  return Number(match[1].replace(',', '.'));
}

Given("le catalogue Horizon est initialise avec les produits de reference", function (this: SouscriptionWorld) {
  assert.deepEqual(this.gateway.products, REFERENCE_PRODUCTS);
});
When("l'utilisateur demande la page du catalogue", async function (this: SouscriptionWorld) {
  await this.open('/catalogue');
});
When("l'utilisateur demande la fiche du produit de code {string}", async function (this: SouscriptionWorld, code: string) {
  await this.open(`/produit/${encodeURIComponent(this.exampleCell(code))}`);
});
When("l'utilisateur recherche un produit sans fournir de code", async function (this: SouscriptionWorld) {
  await this.open('/produit/');
});
When("l'utilisateur soumet le catalogue en methode POST", async function (this: SouscriptionWorld) {
  await this.call('/catalogue.do', 'POST');
});
When("le systeme tente d'ajouter un produit a la liste retournee par le catalogue", async function (this: SouscriptionWorld) {
  await this.open('/catalogue');
  // Le contrat ne declare que GET sur /products : la liste est en lecture seule pour le SPA.
  const { status } = await this.fromSpaOrigin('POST', '/api/v1/products', {
    code: 'NOUVEAU',
    name: 'Horizon Nouveau',
    profile: 'Nouveau',
    annualRate: '0.05',
  });
  this.spaStatus = status;
  if (status === 405) {
    this.raisedError = 'modification interdite';
  }
});
Then("le systeme affiche les {int} placements du catalogue", async function (this: SouscriptionWorld, count: number) {
  await this.page.locator('table.catalogue tbody tr').first().waitFor();
  assert.equal(await this.page.locator('table.catalogue tbody tr').count(), count);
});
Then("le systeme affiche la fiche du produit nomme {string}", async function (this: SouscriptionWorld, name: string) {
  await this.page.locator('section.panel h2').filter({ hasText: name }).waitFor();
  assert.equal(((await this.page.locator('p.intro strong').textContent()) ?? '').trim(), name);
});
Then("le profil affiche est {string}", async function (this: SouscriptionWorld, profile: string) {
  assert.equal(await detailRow(this, 'Profil de risque'), profile);
});
Then("le taux annuel affiche est {string}", async function (this: SouscriptionWorld, rate: string) {
  const shown = displayedPercent(await detailRow(this, 'Taux annuel'));
  assert.equal(shown, Number(toPercent(rate)), `Taux affiche ${shown} % pour un taux annuel de ${rate}.`);
});
Then("le taux affiche en pourcentage est {string}", async function (this: SouscriptionWorld, percent: string) {
  assert.equal(displayedPercent(await detailRow(this, 'Taux annuel')), Number(percent));
});
Then("aucun produit n'est retourne par le catalogue", async function (this: SouscriptionWorld) {
  assert.equal(this.errorPageStatus(), 404);
  assert.equal(await this.page.locator('table.details').count(), 0);
  assert.equal(this.gateway.callsOf('getProduct').filter((c) => c.status === 200).length, 0);
});
Then("une erreur de modification interdite est levee", async function (this: SouscriptionWorld) {
  assert.equal(this.raisedError, 'modification interdite', `Ajout au catalogue non refuse (statut ${this.spaStatus}).`);
  assert.equal(this.gateway.products.length, REFERENCE_PRODUCTS.length);
  await this.open('/catalogue');
  assert.equal(await this.page.locator('table.catalogue tbody tr').count(), REFERENCE_PRODUCTS.length);
});
