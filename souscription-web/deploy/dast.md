# DAST pass - souscription-web (W4-T09)

Definition of the dynamic application security testing pass executed in plan 43. It is the
runtime counterpart of the static escaping contract enforced by `npm run lint`
(`check:no-dom-escape` plus the `no-restricted-syntax` ESLint rules).

## Scope

The journey is anonymous: there is no authentication and no user account, so the crawl runs
unauthenticated and needs no credentials. The SPA is served from the same origin as the gateway
behind the shared ingress, so the scanner targets a single origin.

Seed URLs (the 7 routes of the application):

| Route                           | Notes                                                     |
| ------------------------------- | --------------------------------------------------------- |
| `/catalogue`                    | also `?notice=parcours`, the only query parameter rendered |
| `/produit/{code}`               | `code` is a path parameter echoed through the product lookup |
| `/souscription/souscripteur`    | form: `firstName`, `lastName`, `email`, `birthDate`        |
| `/souscription/simulation`      | form: `initial`, `monthly`, `years`                        |
| `/souscription/recapitulatif`   | renders every stored draft value plus the acceptance box   |
| `/souscription/confirmation`    | renders the server-generated reference                     |
| `/erreur/{code}`                | `code` is a path parameter rendered as text                |

## Crawl rules

1. Walk the funnel in order so each step has a valid server-side draft; the wizard guards
   redirect an incomplete journey back to `/catalogue?notice=parcours`.
2. Replay each step twice: once with the payload set below in every field, once with the payload
   in the path parameters (`/produit/<payload>`, `/erreur/<payload>`).
3. After each submission, re-read the following step and the summary: a payload rejected by the
   gateway must still be echoed back into the form, and a payload accepted into the draft must
   be re-read from the server on `/souscription/recapitulatif` and
   `/souscription/confirmation`. This is where a stored XSS would surface.
4. Scan the responses of the `/api/v1` endpoints too: a JSON body with an HTML content type
   would be an escaping bypass independent of the templates.

## Payload set

Reflected and stored XSS probes, injected in every input and path parameter:

- `<script>alert(1)</script>`
- `"><img src=x onerror=alert(1)>`
- `'"--><svg onload=alert(1)>`
- `javascript:alert(1)` (also as the whole value of a field rendered inside a link)
- `{{constructor.constructor('alert(1)')()}}` (Angular expression injection through a value)
- `%3Cscript%3Ealert(1)%3C%2Fscript%3E` (URL-encoded variant, for the path parameters)

## Pass criteria

- **Zero** findings of reflected or stored cross-site scripting. Any finding fails the pass; no
  risk acceptance is available for this class.
- No response renders a server-provided value as markup: every probe must appear as visible,
  escaped text or be rejected by validation.
- The security headers asserted by `deploy/smoke-headers.sh` (CSP in particular) are present on
  every crawled response.

Findings are reported with the route, the field, the payload and the rendered response, and are
fixed by routing the value through `{{ }}` and a feature `labels.ts` entry - never by escaping
by hand at the call site.
