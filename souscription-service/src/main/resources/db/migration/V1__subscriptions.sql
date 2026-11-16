-- W1-T03 : portage du DDL embarque src/main/resources/db/schema.sql vers une migration
-- Flyway versionnee, forward-only, unique source de verite du schema.
--
-- Option de typage retenue : uuid pour reference et confirmation_key.
-- Aucune donnee H2 existante n'est reprise (la base H2 du monolithe est en memoire et
-- recreee a chaque demarrage), le changement de type VARCHAR(36) -> uuid est donc
-- applique directement en V1 sans migration de donnees intermediaire.
--
-- created_at est stocke en timestamptz et ecrit depuis un Instant : aucune conversion
-- implicite de fuseau n'est introduite ici. Europe/Paris reste un fuseau d'affichage.

CREATE SCHEMA IF NOT EXISTS subscriptions;

CREATE TABLE subscriptions.subscriptions (
  reference        uuid        PRIMARY KEY,
  confirmation_key uuid        NOT NULL UNIQUE,
  product_code     varchar(24) NOT NULL,
  product_name     varchar(80) NOT NULL,
  product_profile  varchar(40) NOT NULL,
  annual_rate      numeric(8,6)  NOT NULL,
  first_name       varchar(80)  NOT NULL,
  last_name        varchar(80)  NOT NULL,
  email            varchar(254) NOT NULL,
  birth_date       date         NOT NULL,
  initial_amount   numeric(14,2) NOT NULL CHECK (initial_amount BETWEEN 100 AND 100000),
  monthly_amount   numeric(14,2) NOT NULL CHECK (monthly_amount BETWEEN 0 AND 2000),
  duration_years   integer       NOT NULL CHECK (duration_years BETWEEN 1 AND 30),
  contributed      numeric(14,2) NOT NULL,
  final_capital    numeric(14,2) NOT NULL,
  created_at       timestamptz   NOT NULL
);
