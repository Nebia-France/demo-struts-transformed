/**
 * W9-T05 label inventory (`npm run check:labels:inventory`).
 *
 * Writes `docs/LABEL_INVENTORY.md`: every label of the SPA, the `labels.ts` module that owns it,
 * and the legacy view text it was ported from. Fails when
 *   1. a component template (external `.html` or inline `template:`) renders a literal text node
 *      or a literal `aria-label` / `title` / `placeholder` / `alt` attribute instead of a label
 *      taken from a `labels.ts` module, or
 *   2. a label duplicates a string owned by the backend tier (the legacy service, model and
 *      repository layers now held by the backend applications): server-owned wording such as a
 *      validation message must reach the SPA through the `FieldErrors` contract, never be
 *      re-declared client-side, otherwise the two copies drift.
 *
 * The legacy view text inventory and the backend string inventory are read from the legacy
 * sources while they exist and snapshotted into `docs/legacy-text-inventory.json`, so the
 * comparison stays runnable once the legacy tier is removed (plan 46).
 */
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import ts from 'typescript';

const root = resolve(import.meta.dirname, '..');
const repo = resolve(root, '../..');
const appDir = join(root, 'src/app');
const legacyViews = join(repo, 'src/main/webapp/WEB-INF/jsp');
const legacyBackend = ['service', 'model', 'repository'].map((d) =>
  join(repo, 'src/main/java/fr/demo/souscription', d),
);
const snapshotPath = join(root, 'docs/legacy-text-inventory.json');
const reportPath = join(root, 'docs/LABEL_INVENTORY.md');

const LETTER = /\p{L}/u;
const norm = (s) => s.replace(/\s+/g, ' ').trim();
const decode = (s) =>
  s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&nbsp;/g, ' ')
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)));

function walk(dir, accept, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, accept, out);
    else if (accept(full)) out.push(full);
  }
  return out;
}

// --- 1. SPA labels, read with the TypeScript parser -------------------------------------------

function labelsOf(file) {
  const source = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true);
  const labels = [];
  const visit = (node, path) => {
    if (ts.isAsExpression(node) || ts.isParenthesizedExpression(node)) return visit(node.expression, path);
    if (ts.isObjectLiteralExpression(node)) {
      for (const prop of node.properties) {
        if (ts.isPropertyAssignment(prop)) visit(prop.initializer, `${path}.${prop.name.getText(source)}`);
      }
      return;
    }
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
      labels.push({ key: path, value: node.text });
    } else if (ts.isArrowFunction(node) && ts.isTemplateExpression(node.body)) {
      const t = node.body;
      const value = t.head.text + t.templateSpans.map((s) => `\${${s.expression.getText(source)}}${s.literal.text}`).join('');
      labels.push({ key: path, value });
    }
  };
  for (const stmt of source.statements) {
    if (!ts.isVariableStatement(stmt)) continue;
    if (!stmt.modifiers?.some((m) => m.kind === ts.SyntaxKind.ExportKeyword)) continue;
    for (const decl of stmt.declarationList.declarations) {
      if (decl.initializer) visit(decl.initializer, decl.name.getText(source));
    }
  }
  return labels;
}

const labelModules = walk(appDir, (f) => f.endsWith('/labels.ts')).sort();
const labels = labelModules.flatMap((file) =>
  labelsOf(file).map((l) => ({ ...l, module: relative(root, file) })),
);

// --- 2. Template literals not sourced from a label module ------------------------------------

function textNodes(html) {
  return html
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/\{\{[\s\S]*?\}\}/g, ' ')
    .replace(/@[a-z]+\s*(\([\s\S]*?\))?\s*\{/g, ' ')
    .replace(/[{}]/g, ' ')
    .replace(/<[^>]*>/g, '\n')
    .split('\n')
    .map((t) => norm(decode(t)))
    .filter((t) => LETTER.test(t));
}

const LITERAL_ATTR = /\s(aria-label|title|placeholder|alt)="([^"]*)"/g;

function templateOffences(file, html) {
  const found = [];
  for (const text of textNodes(html)) found.push(`${file}: text "${text}"`);
  for (const [, attr, value] of html.matchAll(LITERAL_ATTR)) {
    if (LETTER.test(value)) found.push(`${file}: ${attr}="${value}"`);
  }
  return found;
}

const offenders = [];
for (const file of walk(appDir, (f) => f.endsWith('.html'))) {
  offenders.push(...templateOffences(relative(root, file), readFileSync(file, 'utf8')));
}
for (const file of walk(appDir, (f) => f.endsWith('.ts') && !f.endsWith('.spec.ts'))) {
  const code = readFileSync(file, 'utf8');
  for (const [, , body] of code.matchAll(/\btemplate:\s*(['`])([\s\S]*?)\1/g)) {
    offenders.push(...templateOffences(relative(root, file), body));
  }
}

// --- 3. Legacy text inventory (view tier and backend tier) -----------------------------------

function viewTexts(jsp) {
  const body = jsp
    .replace(/<%--[\s\S]*?--%>/g, ' ')
    .replace(/<%[\s\S]*?%>/g, ' ')
    .replace(/\$\{[^}]*\}/g, ' ');
  const texts = textNodes(body);
  for (const [, , value] of body.matchAll(LITERAL_ATTR)) {
    if (LETTER.test(value)) texts.push(norm(decode(value)));
  }
  return texts;
}

function javaStrings(java) {
  return [...java.matchAll(/"((?:[^"\\]|\\.)*)"/g)]
    .map(([, s]) => norm(s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))))
    .filter((s) => /\p{L}{2,}.*\s.*\p{L}/u.test(s));
}

let legacy;
if (existsSync(legacyViews)) {
  const views = {};
  for (const file of walk(legacyViews, (f) => /\.jspf?$/.test(f)).sort()) {
    views[relative(legacyViews, file)] = [...new Set(viewTexts(readFileSync(file, 'utf8')))];
  }
  const backend = {};
  for (const dir of legacyBackend.filter(existsSync)) {
    for (const file of walk(dir, (f) => f.endsWith('.java')).sort()) {
      const strings = [...new Set(javaStrings(readFileSync(file, 'utf8')))];
      if (strings.length) backend[relative(repo, file)] = strings;
    }
  }
  legacy = { views, backend };
  writeFileSync(snapshotPath, JSON.stringify(legacy, null, 2) + '\n');
} else if (existsSync(snapshotPath)) {
  legacy = JSON.parse(readFileSync(snapshotPath, 'utf8'));
} else {
  console.error(`Neither the legacy sources nor ${relative(root, snapshotPath)} are available.`);
  process.exit(1);
}

// --- 4. Attribution and cross-tier duplicates ------------------------------------------------

const staticPart = (v) => norm(v.replace(/\$\{[^}]*\}/g, ' '));
const viewIndex = new Map();
for (const [view, texts] of Object.entries(legacy.views)) {
  for (const t of texts) viewIndex.set(t, [...(viewIndex.get(t) ?? []), view]);
}
const sourceOf = (label) => {
  const value = staticPart(label.value);
  if (!LETTER.test(value)) return '';
  const exact = viewIndex.get(value);
  if (exact) return exact.join(', ');
  const containing = [...viewIndex.entries()].filter(([t]) => t.includes(value) && value.length >= 4);
  return containing.length ? [...new Set(containing.flatMap(([, v]) => v))].join(', ') + ' (part)' : '';
};

const backendIndex = new Map();
for (const [file, strings] of Object.entries(legacy.backend)) {
  for (const s of strings) backendIndex.set(s, [...(backendIndex.get(s) ?? []), file]);
}
// A backend message is often concatenated (`"Saisissez votre " + label + " (1 à 80 ...)."`):
// a label containing any backend fragment of 20 characters or more is a duplicate as well.
const FRAGMENT_MIN = 20;
const backendOf = (value) => {
  const v = norm(value);
  const files = new Set(backendIndex.get(v) ?? []);
  for (const [s, fs] of backendIndex) {
    if (s.length >= FRAGMENT_MIN && v.includes(s)) fs.forEach((f) => files.add(f));
  }
  return files.size ? [...files] : undefined;
};
const duplicates = labels
  .map((l) => ({ ...l, backend: backendOf(l.value) }))
  .filter((l) => l.backend);

const byValue = new Map();
for (const l of labels) {
  if (LETTER.test(l.value)) byValue.set(l.value, [...(byValue.get(l.value) ?? []), l]);
}
const sharedAcrossModules = [...byValue.entries()].filter(
  ([, ls]) => new Set(ls.map((l) => l.module)).size > 1,
);

// --- 5. Report -------------------------------------------------------------------------------

const cell = (s) => String(s).replace(/\|/g, '\\|').replace(/\n/g, ' ');
const lines = [
  '# Label inventory',
  '',
  'Generated by `npm run check:labels:inventory` (`scripts/label-inventory.mjs`); do not edit by hand.',
  '',
  '## Summary',
  '',
  `- Label modules: ${labelModules.length}`,
  `- Labels: ${labels.length}, attributed to a label module: ${labels.length} (100%)`,
  `- Template literals not sourced from a label module: ${offenders.length}`,
  `- Labels duplicating a backend-tier string: ${duplicates.length}`,
  `- Legacy view texts snapshotted: ${Object.values(legacy.views).reduce((n, t) => n + t.length, 0)} ` +
    `in ${Object.keys(legacy.views).length} views (\`docs/legacy-text-inventory.json\`)`,
  '',
  '## Labels by module',
  '',
];
for (const file of labelModules) {
  const mod = relative(root, file);
  lines.push(`### \`${mod}\``, '', '| Key | Value | Legacy view |', '| --- | --- | --- |');
  for (const l of labels.filter((x) => x.module === mod)) {
    lines.push(`| \`${cell(l.key)}\` | ${cell(l.value)} | ${cell(sourceOf(l)) || 'new in the SPA'} |`);
  }
  lines.push('');
}
lines.push(
  '## Values shared by several label modules',
  '',
  'Informational: the same wording owned by two features. Not a cross-tier duplicate.',
  '',
);
if (sharedAcrossModules.length === 0) lines.push('None.');
for (const [value, ls] of sharedAcrossModules) {
  lines.push(`- "${value}": ${ls.map((l) => `\`${l.module}\` \`${l.key}\``).join(', ')}`);
}
lines.push('', '## Cross-tier duplicates', '');
lines.push(duplicates.length ? '' : 'None: no label re-declares a backend-owned string.');
for (const d of duplicates) lines.push(`- \`${d.module}\` \`${d.key}\` = ${d.backend.join(', ')}`);
lines.push('');
writeFileSync(reportPath, lines.join('\n'));

const failures = [
  ...offenders.map((o) => `literal not sourced from a labels.ts module: ${o}`),
  ...duplicates.map((d) => `${d.module} ${d.key} duplicates a backend-tier string (${d.backend.join(', ')}): "${d.value}"`),
];
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(`check:labels:inventory OK (${labels.length} labels in ${labelModules.length} modules)`);
