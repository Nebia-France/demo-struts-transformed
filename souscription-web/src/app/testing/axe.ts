import axe from 'axe-core';

/**
 * Component-level accessibility assertion of the test pyramid (W8-T01).
 *
 * `e2e/a11y` analyses the 7 routes in a real browser; this helper analyses a single rendered
 * component fixture, so a regression in one screen's markup fails in the fast layer instead of
 * waiting for the browser gate. Same blocking policy as `e2e/a11y/axe.ts`: only `serious` and
 * `critical` impacts fail.
 *
 * `color-contrast` is disabled because jsdom has no layout engine and cannot compute rendered
 * colours; the route-level axe run covers it.
 */
const BLOCKING = new Set(['serious', 'critical']);

export async function expectNoA11yViolations(host: HTMLElement): Promise<void> {
  const results = await axe.run(host, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    rules: { 'color-contrast': { enabled: false } },
  });

  const blocking = results.violations.filter((violation) =>
    BLOCKING.has(violation.impact ?? ''),
  );
  expect(blocking.map((violation) => `${violation.impact}: ${violation.id} - ${violation.help}`))
    .toEqual([]);
}
