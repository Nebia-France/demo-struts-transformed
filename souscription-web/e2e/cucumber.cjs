// ts-node must compile the step definitions as CommonJS; the workspace tsconfig.json
// targets the Angular bundler and cannot be reused here.
process.env.TS_NODE_PROJECT = require('path').join(__dirname, 'tsconfig.json');

const { spaNameFilter } = require('./allocation.cjs');

module.exports = {
  // Behavioural gate of souscription-web (W8-T04): the scenarios allocated to the SPA or e2e
  // tier in e2e/SCENARIO_ALLOCATION.md. A pending or undefined step fails the run (strict).
  default: {
    paths: ['e2e/features/**/*.feature'],
    require: ['e2e/steps/**/*.ts'],
    requireModule: ['ts-node/register'],
    name: spaNameFilter(),
    strict: true,
    format: ['progress', 'html:e2e/reports/cucumber.html', 'json:e2e/reports/cucumber.json'],
  },
  // Operational scenarios (resilience contract, replica loss, telemetry sink), outside the 88
  // declared scenarios: `npm run e2e:operational`, strict as well.
  operational: {
    paths: ['e2e/operational/**/*.feature'],
    require: ['e2e/steps/**/*.ts'],
    requireModule: ['ts-node/register'],
    strict: true,
    format: ['progress', 'html:e2e/reports/cucumber-operational.html', 'json:e2e/reports/cucumber-operational.json'],
  },
  // Every scenario, for a replay against a deployed gateway and backend (E2E_BASE_URL).
  all: {
    paths: ['e2e/features/**/*.feature'],
    require: ['e2e/steps/**/*.ts'],
    requireModule: ['ts-node/register'],
    strict: true,
    format: ['progress', 'html:e2e/reports/cucumber-all.html'],
  },
};
