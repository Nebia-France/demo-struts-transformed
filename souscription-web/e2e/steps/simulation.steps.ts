import { Given, Then, When } from '@cucumber/cucumber';
import { strict as assert } from 'node:assert';
import { parseFrAmount, subtractAmounts } from '../../src/app/core/locale/amount';
import { SIMULATION_REJECTED } from '../support/fake-gateway';
import type { SouscriptionWorld } from './world';

// Simulation financiere (source : features/step_definitions/SimulationSteps.java)
// Les montants sont saisis a l'etape 3 du SPA et projetes par la passerelle (POST /simulations,
// taux du produit retenu) ; les valeurs sont lues dans le tableau de resultat rendu par le SPA,
// y compris les gains, que le SPA derive lui-meme (capital final - total verse).

/** Montant fr-FR affiche (`1 000,00 €`) en chaine decimale (`1000.00`). */
function displayedAmount(text: string): string {
  const cleaned = text.replace(/[\s\u00A0\u202F€]/g, '').replace(',', '.');
  assert.match(cleaned, /^-?\d+\.\d{2}$/, `Montant affiche illisible : « ${text} »`);
  return cleaned;
}

function cents(amount: string): bigint {
  return BigInt(amount.replace('.', ''));
}

async function resultRow(world: SouscriptionWorld, label: string): Promise<string> {
  const row = world.page.locator('table.financial tr').filter({ has: world.page.locator('th', { hasText: label }) });
  return ((await row.locator('td').textContent()) ?? '').trim();
}

const result = {
  initial: (w: SouscriptionWorld) => resultRow(w, 'Versement initial').then(displayedAmount),
  monthly: (w: SouscriptionWorld) => resultRow(w, 'Versement mensuel').then(displayedAmount),
  years: (w: SouscriptionWorld) => resultRow(w, 'Durée du placement').then((t) => Number(/\d+/.exec(t)?.[0])),
  contributed: (w: SouscriptionWorld) => resultRow(w, 'Total de vos versements').then(displayedAmount),
  gains: (w: SouscriptionWorld) => resultRow(w, 'Gains estimés').then(displayedAmount),
  finalCapital: (w: SouscriptionWorld) => resultRow(w, 'Capital final estimé').then(displayedAmount),
};

/** Etape 3 atteinte pour le produit retenu (le souscripteur valide est saisi au passage). */
async function onSimulationStep(world: SouscriptionWorld): Promise<void> {
  if (new URL(world.page.url()).pathname === '/souscription/simulation') return;
  await world.captureValidSubscriber(world.retainedProduct ?? 'EQUILIBRE');
}

/** Saisie des montants puis `calculer`, et attente du resultat rendu. */
async function simulate(world: SouscriptionWorld, initial: string, monthly: string, years: string): Promise<void> {
  await onSimulationStep(world);
  await world.fillSimulation({ initial, monthly, years });
  await world.press('Recalculer la simulation');
}

Given("le produit retenu est {string} avec un taux annuel de {string}", function (this: SouscriptionWorld, code: string, taux: string) {
  const product = this.gateway.product(code);
  assert.ok(product, `Produit inconnu : ${code}`);
  product.annualRate = taux;
  this.retainedProduct = code;
});
When("le systeme calcule la simulation avec un initial de {string}, une mensualite de {string} et une duree de {int} ans", async function (this: SouscriptionWorld, initial: string, mensualite: string, duree: number) {
  await simulate(this, initial, mensualite, String(duree));
  assert.equal(this.gateway.lastCall('simulate')?.status, 200, 'La simulation a ete refusee.');
  await this.page.locator('table.financial').waitFor();
});
When("le systeme calcule la simulation avec les parametres bruts initial {string}, mensualite {string}, duree {int} et taux {string}", async function (this: SouscriptionWorld, initial: string, mensualite: string, duree: number, taux: string) {
  const product = this.gateway.product(this.retainedProduct ?? 'EQUILIBRE')!;
  product.annualRate = taux;
  const calls = this.gateway.callsOf('simulate').length;
  await simulate(this, initial.replace('.', ','), mensualite.replace('.', ','), String(duree));
  const answer = this.gateway.callsOf('simulate').slice(calls).at(-1);
  // Refus du moteur : par le validateur client (aucun appel), par la regle serveur (400 avec
  // erreurs de champ) ou par le moteur lui-meme (400 sans champ, page /erreur/400). Dans tous les
  // cas aucun resultat n'est affiche.
  const refused =
    (await this.page.locator('table.financial').count()) === 0 &&
    (answer === undefined || answer.status === 400);
  if (refused) {
    this.raisedError = SIMULATION_REJECTED;
  }
});
When("l'utilisateur renseigne un initial de {string}, une mensualite de {string} et une duree de {string}", async function (this: SouscriptionWorld, initial: string, mensualite: string, duree: string) {
  await onSimulationStep(this);
  await this.fillSimulation({ initial, monthly: mensualite, years: duree });
});
When("le systeme normalise le montant {string}", function (this: SouscriptionWorld, a0: string) {
  this.normalisedAmount = parseFrAmount(a0);
});
Then("aucune erreur de validation de la simulation n'est signalee", async function (this: SouscriptionWorld) {
  await this.flush();
  for (const id of ['initial', 'monthly', 'years']) {
    assert.equal(await this.fieldInError(id), false, `Le champ ${id} est signale en erreur.`);
  }
  assert.equal(this.gateway.lastCall('simulate')?.status, 200);
  await this.page.locator('table.financial').waitFor();
});
Then("la simulation retourne un versement initial de {string}", async function (this: SouscriptionWorld, a0: string) {
  assert.equal(await result.initial(this), a0);
});
Then("la simulation retourne une mensualite de {string}", async function (this: SouscriptionWorld, a0: string) {
  assert.equal(await result.monthly(this), a0);
});
Then("la simulation retourne une duree de {int} ans", async function (this: SouscriptionWorld, a0: number) {
  assert.equal(await result.years(this), a0);
});
Then("le total verse de la simulation est {string}", async function (this: SouscriptionWorld, a0: string) {
  assert.equal(await result.contributed(this), a0);
});
Then("le capital final de la simulation est superieur au total verse", async function (this: SouscriptionWorld) {
  assert.ok(cents(await result.finalCapital(this)) > cents(await result.contributed(this)));
});
Then("le capital final de la simulation est egal au total verse", async function (this: SouscriptionWorld) {
  assert.equal(await result.finalCapital(this), await result.contributed(this));
});
Then("les gains de la simulation sont egaux au capital final moins le total verse", async function (this: SouscriptionWorld) {
  assert.equal(await result.gains(this), subtractAmounts(await result.finalCapital(this), await result.contributed(this)));
});
Then("les gains de la simulation sont nuls", async function (this: SouscriptionWorld) {
  assert.equal(await result.gains(this), '0.00');
});
Then("le montant normalise vaut {string}", function (this: SouscriptionWorld, a0: string) {
  assert.equal(this.normalisedAmount, a0);
});
Then("le montant normalise est absent", function (this: SouscriptionWorld) {
  assert.equal(this.normalisedAmount, null);
});
