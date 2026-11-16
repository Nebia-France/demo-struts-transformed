/**
 * Single parser of the allocation table of `e2e/SCENARIO_ALLOCATION.md` (W8-T04).
 *
 * Shared by `e2e/cucumber.cjs` (name filter of the behavioural gate), `scripts/check-no-pending.mjs`
 * and `scripts/scenario-rate.mjs`, so the three agree on which scenarios belong to the SPA.
 *
 * A scenario is allocated to the SPA when its Tier names the `SPA` or the `e2e` tier; a row that
 * names only backend applications (gateway, services) is asserted by their acceptance suites.
 */
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const ALLOCATION_FILE = join(__dirname, 'SCENARIO_ALLOCATION.md');
const SPA_TIER = /\b(?:SPA|e2e)\b/;

function readAllocation() {
  const md = readFileSync(ALLOCATION_FILE, 'utf8');
  const start = md.indexOf('<!-- allocation-table:start -->');
  const end = md.indexOf('<!-- allocation-table:end -->');
  if (start < 0 || end < start) {
    throw new Error('allocation-table markers not found in e2e/SCENARIO_ALLOCATION.md');
  }
  const rows = [];
  for (const line of md.slice(start, end).split(/\r?\n/)) {
    const cells = line.split('|').slice(1, -1).map((c) => c.trim());
    if (cells.length !== 5 || !/^\d+$/.test(cells[0])) continue;
    const [index, domain, title, tier, tests] = cells;
    rows.push({
      index: Number(index),
      feature: domain.replace(/`/g, '').split('/').pop(),
      title,
      tier,
      tests: [...tests.matchAll(/`([^`]+)`/g)].map((m) => m[1]),
      spa: SPA_TIER.test(tier),
    });
  }
  return rows;
}

function spaRows() {
  return readAllocation().filter((row) => row.spa);
}

/** Anchored regexes of the SPA-allocated titles, for the cucumber-js `name` option. */
function spaNameFilter() {
  return spaRows().map((row) => `^${row.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
}

module.exports = { readAllocation, spaRows, spaNameFilter };
