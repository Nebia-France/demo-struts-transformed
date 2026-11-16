# Former request attributes and their target mechanism

The legacy actions published data to the JSPs by setting named request attributes — a
string-keyed, untyped channel resolved at render time. The SPA has no equivalent: every
value below becomes an explicit, typed mechanism. Plans 13 (shell/layout) and 25 (wizard
screens) must implement them exactly as stated here so that no string-keyed context
attribute is reintroduced.

| Legacy attribute | Set by | Target mechanism |
| --- | --- | --- |
| `product` | `CatalogAction` (product sheet), `SubscriptionAction` (every wizard step) | **Route data** — resolved by `productResolver` for the sheet; inside the wizard it is read from the server-side draft (`DraftView.product`) exposed by the wizard store, then passed down as a **component input**. |
| `errors` | `SubscriptionAction` (subscriber, simulation, acceptance, storage failures) | **Signal** on the wizard store, fed from the gateway `FieldErrors` payload (HTTP 400/409/503) and bound to the reactive form controls; never a free-form map rendered by the template. |
| `step` | `SubscriptionAction.view(...)`, `CatalogAction` (step 1) | **Route data** (`data: { step: n }` on each wizard route), read by the progress indicator as a **component input**. |
| `pageTitle` | `CatalogAction`, `SubscriptionAction.view(...)` | **`Title` service** (`@angular/platform-browser`), set from the route `title` property so the document title and the heading stay in sync. |

Rules that follow from the table:

- No service, component or interceptor may carry a string-keyed bag of view data.
- `step` and `pageTitle` are static per route: they belong to the route definition, not to
  component code.
- `product` and `errors` are dynamic and server-derived: they belong to the store
  (signals) and travel downwards as inputs only.
