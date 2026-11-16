#!/usr/bin/env bash
#
# Development / test certificates for the mTLS transport (W6-T01).
#
# Generated at build time (generate-test-resources), never committed: the output
# directory is the reactor's own build directory, outside the module tree, so the
# "no secret material in the module" check
#   grep -rniE 'BEGIN (RSA |EC )?PRIV''ATE KEY|BEGIN CERTIFI''CATE|pass''word=' souscription-simulation-service/
# stays empty even after a build. The cluster PKI is the only source of real
# certificates; these are throwaway self-signed material for @QuarkusTest.
#
# Three identities are produced:
#   server  CN=souscription-simulation-service, SAN DNS:localhost  - the service itself
#   client  SAN URI:spiffe://.../souscription-gateway              - a contracted caller
#   rogue   SAN URI:spiffe://.../souscription-web                  - a PKI-valid but
#           uncontracted caller, used to prove the identity allow-list returns 403
#
# No passphrase is used anywhere: the keys are PKCS#8 PEM, loaded directly by the
# test harness, so no password ever appears in a property file or in the image.
set -euo pipefail

OUT_DIR="${1:?usage: generate-dev-certs.sh <output-directory>}"
SPIFFE_PREFIX="spiffe://horizon-souscription/ns/horizon/sa"

mkdir -p "$OUT_DIR"

gen() {
  local name="$1" subject="$2" san="$3" conf
  conf="$(mktemp)"
  cat >"$conf" <<EOF
[req]
distinguished_name = dn
prompt = no
[dn]
CN = ${name}
[ext]
basicConstraints = critical,CA:TRUE
keyUsage = digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth,clientAuth
subjectAltName = ${san}
EOF
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$OUT_DIR/$name-key.pem" \
    -out "$OUT_DIR/$name-cert.pem" -days 365 -sha256 \
    -subj "$subject" -config "$conf" -extensions ext >/dev/null 2>&1
  rm -f "$conf"
}

gen server "/CN=souscription-simulation-service" "DNS:localhost,IP:127.0.0.1"
gen client "/CN=souscription-gateway" "URI:${SPIFFE_PREFIX}/souscription-gateway"
gen rogue  "/CN=souscription-web" "URI:${SPIFFE_PREFIX}/souscription-web"

# Server-side trust anchors: both client identities are trusted at the TLS layer so
# that the allow-list, not the PKI, is what rejects the uncontracted one (403).
cat "$OUT_DIR/client-cert.pem" "$OUT_DIR/rogue-cert.pem" >"$OUT_DIR/client-ca.pem"

# Client-side material for the @QuarkusTest harness. RestAssured reads JSSE stores,
# not PEM, so each client identity is bundled as a PKCS#12 keystore and the server
# certificate as a PKCS#12 truststore. The store passphrase is a throwaway value of
# the build directory, read by the harness from this same constant; it protects
# nothing (the keys next to it are unencrypted) and never reaches the image.
STORE_SECRET="devstore"

p12() {
  local name="$1"
  openssl pkcs12 -export -name "$name" \
    -inkey "$OUT_DIR/$name-key.pem" -in "$OUT_DIR/$name-cert.pem" \
    -out "$OUT_DIR/$name-keystore.p12" -passout "pass:$STORE_SECRET"
}

p12 client
p12 rogue

# Re-generated from scratch on every build: keytool refuses to re-import an
# existing alias, so an incremental build must not inherit the previous store.
rm -f "$OUT_DIR/truststore.p12"
keytool -importcert -noprompt -alias server \
  -file "$OUT_DIR/server-cert.pem" \
  -keystore "$OUT_DIR/truststore.p12" -storetype PKCS12 \
  -storepass "$STORE_SECRET" >/dev/null

echo "dev certificates generated in $OUT_DIR"
