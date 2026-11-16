# Cutover record: legacy presentation tier removal (plan 46, W9-T06)

Plan 46 deletes the 22 files of the Struts/JSP presentation tier. It is the point of no return:
before it, rollback is an ingress change; after it, rollback is a `git revert` plus an artifact
version rollback. It runs only after the MANUAL_REVIEW gate below has been signed off.

## Status

**GATE NOT SIGNED OFF. REMOVAL NOT EXECUTED.** The WAR (`horizon.war`, root `pom.xml`) is still
deployable and is still the only implementation of the server-side behaviour (CSRF filter,
security headers, journey actions, persistence).

| Entry criterion | State | Evidence |
| --- | --- | --- |
| 1. Plans 41-45 executed with every exit criterion green | Met for the SPA, with caveats | commits `fee001bc` (44), `74fecdb2` (43), `dbef02e6`/`57f923dd` (45). Plans 43 and 45 are recorded as **NOT COMPLETE**: they ran against the gateway double, so there are no provider measurements |
| 2. Traffic switched to the SPA behind the shared ingress, observation period over | **Not met** | `docs/CONTRACT_VALIDATION.md`: "No deployed `souscription-gateway` answers on the SPA origin yet" |
| 3. 88/88 Gherkin scenarios green | Met before removal | `e2e/reports/scenario-rate.json` (spa 61/61, backend 27/27). The backend 27 replay the **legacy reference implementation** of this repository |
| 4. Human sign-off recorded here | **Not met** | see the sign-off table below |

## Blockers measured on 2026-11-14

I applied the 22 deletions in a throwaway `git worktree` (since removed) and ran `mvn -q test` with
JDK 8:

1. **Main code no longer compiles.** `service/InputValidator.java` imports
   `fr.demo.souscription.form.SubscriptionForm` (in `subscriberErrors`, `subscriber` and
   `simulationErrors`), so `mvn -q test` exits 1. The preserved test `InputValidatorTest` also
   builds `SubscriptionForm` instances. Plan 46 lists `SubscriptionForm.java` for deletion but also
   forbids touching `service/`. Before the removal, the owner of `InputValidator`
   (`souscription-service`) must replace the `SubscriptionForm` parameter, for example with the
   `SubscriberInput`/`SimulationInput` DTOs.
2. **The backend Gherkin tier loses its subject.** `acceptance/support/LegacyHttp.java` and
   `BackendCommonSteps.java` drive `SecurityFilter`, `CatalogAction`, `SubscriptionAction` and
   `SubscriptionForm`, and import `org.apache.struts.action.*`. Nine of the 27 backend-allocated
   scenarios replay through that pipeline:
   - `catalogue.feature:58`
   - `parcours-souscription.feature:81`
   - `confirmation-souscription.feature:28` and `:41`
   - `securite-web.feature:20`, `:44`, `:63`, `:69` and `:83`

   Because the deleted classes break compilation, all 27 would turn red, so `check:rate` would
   report 61/88. These scenarios must first be re-homed to the suites of `souscription-gateway`
   (CSRF, headers, methods, encoding) and `souscription-service` (journey and confirmation). Then
   `BACKEND_CUCUMBER_REPORT` must point at those reports. Plan 46 cannot remove these tests itself:
   only `SubscriptionIT.java` may be removed.
3. **A security control would be removed with no deployed replacement.** `SecurityFilter`
   provides CSRF, security headers and the method allow-list. Its replacement,
   `souscription-gateway`, is not deployed on the SPA origin (criterion 2).

## Sign-off (to be completed by a human)

| Role | Name | Date | Decision |
| --- | --- | --- | --- |
| Product owner | _pending_ | | |
| Platform / ingress owner | _pending_ | | |
| Security | _pending_ | | |

Sign-off confirms that plans 41-45 are green against the **deployed** provider, that traffic has
gone through the SPA ingress for the whole observation period, and that blockers 1-2 are resolved.

## Removal procedure (once signed off)

Delete each file by name. Never delete a directory recursively. Put only these deletions and the
`pom.xml` edit in a single commit, so that the rollback is one `git revert`.

```sh
git rm src/main/webapp/WEB-INF/struts-config.xml \
  src/main/webapp/WEB-INF/jsp/catalogue.jsp src/main/webapp/WEB-INF/jsp/produit.jsp \
  src/main/webapp/WEB-INF/jsp/souscripteur.jsp src/main/webapp/WEB-INF/jsp/simulation.jsp \
  src/main/webapp/WEB-INF/jsp/recapitulatif.jsp src/main/webapp/WEB-INF/jsp/confirmation.jsp \
  src/main/webapp/WEB-INF/jsp/erreur.jsp \
  src/main/webapp/WEB-INF/jsp/fragments/header.jspf src/main/webapp/WEB-INF/jsp/fragments/footer.jspf \
  src/main/webapp/WEB-INF/jsp/fragments/errors.jspf src/main/webapp/WEB-INF/jsp/fragments/result.jspf \
  src/main/webapp/WEB-INF/jsp/fragments/csrf.jspf \
  src/main/webapp/assets/css/application.css \
  src/main/java/fr/demo/souscription/action/CatalogAction.java \
  src/main/java/fr/demo/souscription/action/SubscriptionAction.java \
  src/main/java/fr/demo/souscription/form/SubscriptionForm.java \
  src/main/java/fr/demo/souscription/web/SecurityFilter.java \
  src/main/webapp/WEB-INF/web.xml src/main/webapp/META-INF/context.xml \
  src/main/tomcat/server.xml \
  src/test/java/fr/demo/souscription/web/SubscriptionIT.java
```

In coordination with the `souscription-gateway` plan, then remove the `struts-core`,
`struts-taglib`, JSTL and JSP API dependencies, the `cargo-maven3-plugin` configuration and the
`integration` profile from `pom.xml`. Change `<packaging>war</packaging>` only if the module no
longer packages a web tier.

Then check: `mvn -q test` exits 0, and
`cd frontend/souscription-web && npm ci && npm run lint && npm run build && npm test && npm run e2e && npm run check:rate`
reports 88/88.

## Rollback

- **Before the removal (current state).** Point the shared ingress back at the WAR
  (`/horizon/*` served by Tomcat) and redeploy the last `horizon.war` artifact. No code change is
  needed.
- **After the removal.** Run `git revert <removal-commit-sha>` to restore the 22 files and the
  `pom.xml` entries. Rebuild and redeploy `horizon.war` at the artifact version recorded before the
  removal (artifact version rollback). Then switch the ingress back to the WAR.

## Parity check (plan 46 parity, 2026-11-14)

The six parity steps were run against the current tree. The removal has not been executed, so they
check the pre-removal state.

| Step | Result | Evidence |
| --- | --- | --- |
| 1. The 22 named files are gone | **FAIL (expected, gated)** | all 22 files are still present. The removal waits for the sign-off above |
| 2. The four preserved tests exist and pass | PASS | all four present. `mvn -q test` exits 0 on JDK 8 (39 backend acceptance rows green) |
| 3. The business layers are untouched | PASS | `service/`, `model/`, `repository/` and `web/Application.java` are present |
| 4. No dangling reference | PASS for the SPA | `check:no-struts` exits 0. The grep matches in `frontend/souscription-web/src` and `deploy/` are historical comments only. The matches in `src/` and `pom.xml` are the legacy tier itself, plus blocker 1 (`InputValidator`) and blocker 2 (`acceptance/support`) |
| 5. 88/88 scenarios green | PASS (pre-removal) | `npm run e2e` reports 128/128 rows. `check:rate` reports 88/88 (spa 61/61, backend 27/27) |
| 6. Commit shape and cutover record | PASS for the record | this file records the sign-off table and both rollback procedures. There is no removal commit to inspect yet |

Build gate: `npm ci`, `npm run lint`, `npm run build` and `npm test` (242/242) pass, and so does
`npm run e2e`.

Remediation R1a (deleting the files by name) was not applied, for four reasons:

- Applying it fails step 2: `InputValidator` no longer compiles (blocker 1).
- R2b and R3 forbid the fix, which is to edit `service/`.
- R5 then requires reverting the removal anyway: `check:rate` would drop to 61/88 (blocker 2).
- Deleting `SecurityFilter` would remove a security control that has no deployed replacement
  (blocker 3).

The parity loop therefore stays in the pre-removal state until the sign-off is recorded and
blockers 1-3 are resolved. At that point, run the removal procedure above and then re-run this
check.
