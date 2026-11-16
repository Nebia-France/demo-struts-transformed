import { Then, When } from '@cucumber/cucumber';
import { strict as assert } from 'node:assert';
import {
  ACCEPTED_DEFAULT,
  SIMULATION_DEFAULTS,
  SUBSCRIBER_DEFAULTS,
  safeText,
} from '../../src/app/wizard/form-defaults';
import type { SouscriptionWorld } from './world';

// Formulaire de souscription (source : features/step_definitions/FormulaireSouscriptionSteps.java)
// Les defauts et la regle safe() du SubscriptionForm sont portes par les groupes reactifs
// typees ; les etapes travaillent directement sur cette source de verite, sans navigateur.

When("le systeme cree un nouveau formulaire de souscription", function (this: SouscriptionWorld) {
  this.formValues = { ...SUBSCRIBER_DEFAULTS, ...SIMULATION_DEFAULTS };
  this.formAccepted = ACCEPTED_DEFAULT;
});
When("l'utilisateur renseigne le champ {string} avec une valeur nulle", function (this: SouscriptionWorld, champ: string) {
  this.formValues = { ...(this.formValues ?? {}), [champ]: safeText(null) };
});
When("l'utilisateur renseigne le champ {string} avec la valeur {string}", function (this: SouscriptionWorld, champ: string, valeur: string) {
  this.formValues = { ...(this.formValues ?? {}), [champ]: safeText(valeur) };
});
When("l'utilisateur accepte les informations et les hypotheses de simulation", function (this: SouscriptionWorld) {
  this.formAccepted = true;
});
Then("le versement initial par defaut est {string}", function (this: SouscriptionWorld, attendu: string) {
  assert.equal(SIMULATION_DEFAULTS.initial, attendu);
});
Then("la mensualite par defaut est {string}", function (this: SouscriptionWorld, attendu: string) {
  assert.equal(SIMULATION_DEFAULTS.monthly, attendu);
});
Then("la duree par defaut est {string}", function (this: SouscriptionWorld, attendu: string) {
  assert.equal(SIMULATION_DEFAULTS.years, attendu);
});
Then("les champs d'identification du souscripteur sont vides", function (this: SouscriptionWorld) {
  for (const champ of ['firstName', 'lastName', 'email', 'birthDate']) {
    assert.equal(this.formValues?.[champ], '');
  }
});
Then("l'acceptation des informations est a faux", function (this: SouscriptionWorld) {
  assert.equal(this.formAccepted, false);
});
Then("l'acceptation des informations est a vrai", function (this: SouscriptionWorld) {
  assert.equal(this.formAccepted, true);
});
Then("le champ {string} du formulaire vaut une chaine vide", function (this: SouscriptionWorld, champ: string) {
  assert.equal(this.formValues?.[champ], '');
});
Then("le champ {string} du formulaire vaut {string}", function (this: SouscriptionWorld, champ: string, attendu: string) {
  assert.equal(this.formValues?.[champ], attendu);
});
