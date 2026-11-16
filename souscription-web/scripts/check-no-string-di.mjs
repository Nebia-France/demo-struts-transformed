/**
 * Fails the build when dependency injection is resolved by name instead of by token.
 *
 * The legacy tier resolved its actions by class name from `struts-config.xml` and its
 * collaborators from a string keyed servlet-context attribute. Nothing of that kind may
 * survive in the SPA: `inject(...)` must always receive a class or an `InjectionToken`
 * identifier, never a string literal, so every wiring error is a compile error.
 */
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../src');

/** inject('x') / inject("x") / inject(`x`) — injection by string literal. */
const stringInject = /\binject\(\s*['"`]/;
/** TestBed.inject('x') and the like. */
const stringTestBedInject = /\.inject\(\s*['"`]/;
/** new InjectionToken resolved by name rather than held as an exported const. */
const tokenByName = /\bInjectionToken\s*\)|\bInjectionToken<[^>]*>\s*\)/;

const offenders = [];

const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
      continue;
    }
    if (!full.endsWith('.ts')) continue;
    readFileSync(full, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (stringInject.test(line) || stringTestBedInject.test(line) || tokenByName.test(line)) {
          offenders.push(`${full}:${i + 1}: ${line.trim()}`);
        }
      });
  }
};

if (existsSync(root)) walk(root);

if (offenders.length) {
  console.error(
    'String-based dependency resolution found. Inject class or InjectionToken identifiers only.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:di OK');
