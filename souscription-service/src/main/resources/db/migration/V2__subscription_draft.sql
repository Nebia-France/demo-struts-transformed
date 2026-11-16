-- W2-T01 : le brouillon de parcours, jusqu'ici un objet Draft serialise en Java porte par
-- session HTTP de servlet, devient une ligne dediee du schema subscriptions avec une expiration explicite.
--
-- draft_id est un UUIDv4 opaque et non enumerable : c'est le seul jeton porte par le SPA
-- (mitigation R-002). Jamais un compteur, une sequence ou une valeur devinable.
--
-- Les colonnes souscripteur et simulation sont nullables : elles reproduisent les etats
-- intermediaires du parcours (draft.getSubscriber() == null, draft.getSimulation() == null).
--
-- version prepare le verrouillage optimiste introduit par 23-souscription-service-idempotent-confirm
-- (AG-016, CFG-012). expires_at materialise la retention, cablee par
-- 13-souscription-service-draft-retention-purge (equivalent du <session-timeout>20</session-timeout>).
--
-- Le produit est copie dans le brouillon (code, nom, profil, taux annuel), exactement comme le
-- Draft source portait un Product complet : aucune cle etrangere vers un catalogue, le catalogue
-- etant un service distant sans datastore propre.
--
-- Migration forward-only et additive.

CREATE TABLE subscriptions.subscription_draft (
  draft_id         uuid         PRIMARY KEY,
  confirmation_key uuid         NOT NULL,
  product_code     varchar(24)  NOT NULL,
  product_name     varchar(80)  NOT NULL,
  product_profile  varchar(40)  NOT NULL,
  annual_rate      numeric(8,6) NOT NULL,
  first_name       varchar(80),
  last_name        varchar(80),
  email            varchar(254),
  birth_date       date,
  initial_amount   numeric(14,2),
  monthly_amount   numeric(14,2),
  duration_years   integer,
  contributed      numeric(14,2),
  final_capital    numeric(14,2),
  version          integer      NOT NULL DEFAULT 0,
  created_at       timestamptz  NOT NULL,
  updated_at       timestamptz  NOT NULL,
  expires_at       timestamptz  NOT NULL
);

CREATE INDEX idx_subscription_draft_expires_at
  ON subscriptions.subscription_draft (expires_at);
