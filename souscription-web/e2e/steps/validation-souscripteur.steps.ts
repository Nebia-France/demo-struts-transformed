import { Given, Then, When } from '@cucumber/cucumber';
import { strict as assert } from 'node:assert';
import type { SouscriptionWorld } from './world';

// Validation du souscripteur (source : features/step_definitions/ValidationSouscripteurSteps.java)
// Saisie dans l'etape 2 du SPA. Les refus immediats (champ requis, longueur, format de date) sont
// ceux des validateurs reactifs ; les autres sont ceux d'InputValidator, rendus par le SPA a
// partir des erreurs de champ RFC 7807 de la passerelle. Le jeu de controles, quelle que soit sa
// source, est observe sur l'ecran : `aria-invalid` et le message sous le champ.

/** Soumet la saisie en attente ; vrai quand le SPA reste sur l'etape 2 (saisie refusee). */
async function stayedOnSubscriberStep(world: SouscriptionWorld): Promise<boolean> {
  await world.flush();
  return new URL(world.page.url()).pathname === '/souscription/souscripteur';
}

Given("la date du jour du systeme est le {string}", function (this: SouscriptionWorld, date: string) {
  const [day, month, year] = date.split('/');
  this.gateway.today = `${year}-${month}-${day}`;
});
Given("le formulaire de souscription est vide", async function (this: SouscriptionWorld) {
  assert.ok(await this.startJourney('PRUDENT'));
  for (const id of ['firstName', 'lastName', 'email', 'birthDate']) {
    assert.equal(await this.page.inputValue(`input#${id}`), '');
  }
});
When("l'utilisateur renseigne le prenom {string} et le nom {string}", async function (this: SouscriptionWorld, prenom: string, nom: string) {
  // Les exemples nomment les valeurs trop longues au lieu de les ecrire.
  const expand = (value: string) => {
    const named = /^(?:PRENOM|NOM)_DE_(\d+)_CARACTERES$/.exec(value);
    return named ? 'a'.repeat(Number(named[1])) : value;
  };
  await this.fillSubscriber({ firstName: expand(prenom), lastName: expand(nom) });
});
When("l'utilisateur renseigne l'adresse electronique {string}", async function (this: SouscriptionWorld, email: string) {
  await this.fillSubscriber({ email });
});
When("l'utilisateur renseigne la date de naissance {string}", async function (this: SouscriptionWorld, date: string) {
  await this.fillSubscriber({ birthDate: date });
});
When("l'utilisateur renseigne un prenom de {int} caracteres et le nom {string}", async function (this: SouscriptionWorld, longueur: number, nom: string) {
  await this.fillSubscriber({ firstName: 'a'.repeat(longueur), lastName: nom });
});
When("l'utilisateur renseigne un nom contenant un caractere de controle", async function (this: SouscriptionWorld) {
  await this.fillSubscriber({
    firstName: 'Camille',
    lastName: 'Dur\u0007and',
    email: 'camille@example.fr',
    birthDate: '12/04/1985',
  });
});
When("l'utilisateur renseigne une adresse electronique de {int} caracteres", async function (this: SouscriptionWorld, longueur: number) {
  const domain = '@example.fr';
  await this.fillSubscriber({
    firstName: 'Camille',
    lastName: 'Durand',
    email: `${'a'.repeat(longueur - domain.length)}${domain}`,
    birthDate: '12/04/1985',
  });
});
When("le systeme tente de construire le souscripteur", async function (this: SouscriptionWorld) {
  await this.submitSubscriber();
  // Construction refusee : l'etape est reaffichee en erreur et aucun souscripteur n'est retenu.
  const refused =
    new URL(this.page.url()).pathname === '/souscription/souscripteur' &&
    !this.gateway.currentDraft()?.subscriber &&
    (await Promise.all(['firstName', 'lastName', 'email', 'birthDate'].map((id) => this.fieldInError(id)))).some(Boolean);
  if (refused) {
    this.raisedError = 'Souscripteur invalide';
  }
});
Then("aucune erreur de validation du souscripteur n'est signalee", async function (this: SouscriptionWorld) {
  assert.equal(await stayedOnSubscriberStep(this), false, "L'etape 2 a ete reaffichee en erreur.");
  await this.expectLocation('/souscription/simulation');
  assert.equal(this.gateway.lastCall('putDraftSubscriber')?.status, 200);
});
Then("le souscripteur construit porte le prenom {string} et le nom {string}", async function (this: SouscriptionWorld, prenom: string, nom: string) {
  const subscriber = this.gateway.currentDraft()?.subscriber;
  assert.equal(subscriber?.firstName, prenom);
  assert.equal(subscriber?.lastName, nom);
  // Le SPA reaffiche le souscripteur retenu par le serveur.
  await this.open('/souscription/souscripteur');
  assert.equal(await this.page.inputValue('input#firstName'), prenom);
  assert.equal(await this.page.inputValue('input#lastName'), nom);
});
Then("l'adresse electronique du souscripteur est {string}", async function (this: SouscriptionWorld, email: string) {
  assert.equal(this.gateway.currentDraft()?.subscriber?.email, email);
  await this.open('/souscription/souscripteur');
  assert.equal(await this.page.inputValue('input#email'), email);
});
Then("la date de naissance du souscripteur est affichee {string}", async function (this: SouscriptionWorld, date: string) {
  // Reaffichage de l'etape 2 depuis le brouillon serveur (date ISO rendue en JJ/MM/AAAA).
  await this.open('/souscription/souscripteur');
  assert.equal(await this.page.inputValue('input#birthDate'), date);
});
Then("le champ {string} est signale en erreur", async function (this: SouscriptionWorld, champ: string) {
  assert.ok(await stayedOnSubscriberStep(this), "L'etape 2 a ete franchie malgre la saisie invalide.");
  assert.ok(await this.fieldInError(champ), `Le champ ${champ} n'est pas signale en erreur.`);
  assert.notEqual(await this.fieldMessage(champ), '', `Aucun message sous le champ ${champ}.`);
});
Then("le champ {string} est signale en erreur avec le message contenant {string}", async function (this: SouscriptionWorld, champ: string, message: string) {
  assert.ok(await stayedOnSubscriberStep(this), "L'etape 2 a ete franchie malgre la saisie invalide.");
  assert.ok(await this.fieldInError(champ), `Le champ ${champ} n'est pas signale en erreur.`);
  const shown = await this.fieldMessage(champ);
  assert.ok(shown.includes(message), `Message du champ ${champ} : « ${shown} » ne contient pas « ${message} ».`);
});
