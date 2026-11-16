#!/usr/bin/env bash
# Demarrage local des cinq applications reelles + PostgreSQL + entree HTTPS.
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.horizon"
ENV_FILE="$STATE/local.env"
PKI="$STATE/pki"
ACTION="${1:-up}"
NO_BUILD=false
LOCKED=false

usage() {
  cat <<'EOF'
Usage: ./scripts/start-all.sh [up [--no-build]|check|status|logs [service]|down|help]

  up          Genere les secrets/certificats locaux, construit, demarre et teste.
  --no-build  Reutilise les images deja construites.
  check       Verifie le web puis un parcours complet de souscription (donnees fictives).
  status      Affiche l'etat des conteneurs.
  logs        Suit les journaux (Ctrl+C quitte les logs sans arreter les services).
  down        Arrete les conteneurs ; conserve la base et les secrets locaux.

Prerequis : Docker avec Compose v2+, Bash, curl et OpenSSL.
Premier lancement : Internet requis pour les images et les dependances Maven/npm.
URL : https://localhost:8443 (port personnalisable avec HORIZON_HTTPS_PORT).
EOF
}
fail() { printf 'Erreur : %s\n' "$*" >&2; exit 1; }
compose() {
  docker compose --project-directory "$ROOT" --env-file "$ENV_FILE" \
    -f "$ROOT/compose.local.yaml" "$@"
}
cleanup() {
  if $LOCKED; then rmdir "$STATE/start.lock" 2>/dev/null || true; fi
}
diagnose() {
  local status=$?
  trap - ERR
  printf '\nEchec du lancement ou du parcours. Les conteneurs restent disponibles pour diagnostic.\n' >&2
  compose ps --all >&2 || true
  compose logs --no-color --tail=60 >&2 || true
  exit "$status"
}

case "$ACTION" in
  -h|--help|help) usage; exit 0 ;;
  up|check|status|logs|down) ;;
  *) usage >&2; exit 2 ;;
esac
if [[ "$ACTION" == up && "${2:-}" == --no-build && $# -eq 2 ]]; then
  NO_BUILD=true
elif [[ "$ACTION" == logs && $# -le 2 ]]; then
  :
elif [[ $# -gt 1 ]]; then
  usage >&2; exit 2
fi
command -v docker >/dev/null || fail 'Docker est requis.'
docker compose version >/dev/null || fail 'Le plugin Docker Compose v2+ est requis.'
docker info >/dev/null 2>&1 || fail 'Le moteur Docker est inaccessible. Demarrer Docker Desktop ou verifier les permissions.'

if [[ "$ACTION" == up ]]; then
  for tool in curl openssl; do command -v "$tool" >/dev/null || fail "$tool est requis."; done
  # Tout le repertoire contenant les secrets est prive sur la machine hote.
  umask 077
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  mkdir "$STATE/start.lock" 2>/dev/null || fail "Un lancement est deja en cours ($STATE/start.lock)."
  LOCKED=true
  trap cleanup EXIT
  if [[ ! -f "$ENV_FILE" ]]; then
    {
      printf 'LOCAL_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
      printf 'LOCAL_CSRF_KEY=%s\n' "$(openssl rand -hex 32)"
      printf 'LOCAL_STORE_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    } >"$ENV_FILE.tmp"
    mv "$ENV_FILE.tmp" "$ENV_FILE"
  fi
  chmod 600 "$ENV_FILE"
  # Le port reste identique entre up/check/down, sauf surcharge explicite.
  if [[ -n "${HORIZON_HTTPS_PORT:-}" ]]; then
    printf '%s\n' "$HORIZON_HTTPS_PORT" >"$STATE/https-port"
  elif [[ -f "$STATE/https-port" ]]; then
    IFS= read -r HORIZON_HTTPS_PORT <"$STATE/https-port"
  else
    HORIZON_HTTPS_PORT=8443
    printf '%s\n' "$HORIZON_HTTPS_PORT" >"$STATE/https-port"
  fi
else
  [[ -f "$ENV_FILE" ]] || fail 'Aucun environnement initialise. Executer ./scripts/start-all.sh.'
  if [[ -z "${HORIZON_HTTPS_PORT:-}" && -f "$STATE/https-port" ]]; then
    IFS= read -r HORIZON_HTTPS_PORT <"$STATE/https-port"
  fi
fi
HORIZON_HTTPS_PORT="${HORIZON_HTTPS_PORT:-8443}"
case "$HORIZON_HTTPS_PORT" in ''|*[!0-9]*) fail 'HORIZON_HTTPS_PORT doit etre un numero de port.' ;; esac
[[ ${#HORIZON_HTTPS_PORT} -le 5 ]] || fail 'Port hors de la plage 1..65535.'
[[ "$HORIZON_HTTPS_PORT" -ge 1 && "$HORIZON_HTTPS_PORT" -le 65535 ]] || fail 'Port hors de la plage 1..65535.'
export HORIZON_HTTPS_PORT
BASE_URL="https://localhost:$HORIZON_HTTPS_PORT"

public_images_config() {
  local endpoint plugins name path
  # Toutes les images de cette pile sont publiques. Sur un moteur Unix local,
  # evite un appel inutile au trousseau Docker Desktop (qui peut rester bloque).
  # Les contextes distants et les preferences Docker de l'utilisateur sont conserves.
  [[ "${HORIZON_USE_DOCKER_CONFIG:-0}" == 1 ]] && return 0
  endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
  endpoint="${DOCKER_HOST:-$endpoint}"
  case "$endpoint" in unix://*) ;; *) return 0 ;; esac
  plugins="$(docker info --format '{{range .ClientInfo.Plugins}}{{if or (eq .Name "compose") (eq .Name "buildx")}}{{.Name}}={{.Path}}{{println}}{{end}}{{end}}')"
  mkdir -p "$STATE/docker-public/cli-plugins"
  while IFS='=' read -r name path; do
    case "$name" in
      compose|buildx) [[ -x "$path" ]] && ln -sf "$path" "$STATE/docker-public/cli-plugins/docker-$name" ;;
    esac
  done <<<"$plugins"
  export DOCKER_CONFIG="$STATE/docker-public"
  export DOCKER_HOST="$endpoint"
  unset DOCKER_CONTEXT
}

generate_pki() {
  local service ext
  mkdir -p "$PKI"
  # Les UID non-root des conteneurs doivent lire les montages ; .horizon reste 0700.
  chmod 755 "$PKI"
  if [[ -f "$PKI/.complete" ]]; then
    for service in ca localhost souscription-gateway souscription-service souscription-catalogue-service souscription-simulation-service; do
      openssl x509 -checkend 86400 -noout -in "$PKI/$service.crt" >/dev/null 2>&1 || \
        fail "Certificat local expire/invalide : $service. Arreter la pile, supprimer uniquement .horizon/pki puis relancer."
    done
    return
  fi
  printf '\nGeneration des certificats HTTPS et mTLS locaux...\n'
  # Ne charge jamais le fichier d'environnement comme un script shell.
  while IFS='=' read -r name value; do
    if [[ "$name" == LOCAL_STORE_PASSWORD ]]; then export LOCAL_STORE_PASSWORD="$value"; fi
  done <"$ENV_FILE"
  [[ -n "${LOCAL_STORE_PASSWORD:-}" ]] || fail 'LOCAL_STORE_PASSWORD absent de .horizon/local.env.'
  cat >"$PKI/ca.conf" <<'EOF'
[req]
distinguished_name=dn
x509_extensions=ca
prompt=no
[dn]
CN=Horizon Local Development CA
[ca]
basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign,cRLSign
EOF
  openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 365 \
    -config "$PKI/ca.conf" -keyout "$PKI/ca.key" -out "$PKI/ca.crt" >/dev/null 2>&1
  for service in localhost souscription-gateway souscription-service souscription-catalogue-service souscription-simulation-service; do
    ext="$PKI/leaf.conf"
    {
      printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth,clientAuth\n'
      if [[ "$service" == localhost ]]; then
        printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\n'
      else
        printf 'subjectAltName=DNS:%s,URI:spiffe://horizon-souscription/ns/horizon/sa/%s\n' "$service" "$service"
      fi
    } >"$ext"
    openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$service" \
      -keyout "$PKI/$service.key" -out "$PKI/$service.csr" >/dev/null 2>&1
    openssl x509 -req -in "$PKI/$service.csr" -CA "$PKI/ca.crt" -CAkey "$PKI/ca.key" \
      -CAserial "$PKI/ca.srl" -CAcreateserial -out "$PKI/$service.crt" -days 30 -sha256 -extfile "$ext" >/dev/null 2>&1
  done
  openssl pkcs12 -export -name souscription-gateway -inkey "$PKI/souscription-gateway.key" \
    -in "$PKI/souscription-gateway.crt" -certfile "$PKI/ca.crt" \
    -out "$PKI/souscription-gateway.p12" -passout env:LOCAL_STORE_PASSWORD
  # keytool importe une ancre de confiance Java ; aucun JDK n'est requis sur l'hote.
  rm -f "$PKI/truststore.p12"
  docker run --rm --env-file "$ENV_FILE" --mount "type=bind,src=$PKI,dst=/pki" \
    eclipse-temurin:21-jre-jammy keytool -importcert -noprompt -alias horizon-local \
      -file /pki/ca.crt -keystore /pki/truststore.p12 -storetype PKCS12 \
      -storepass:env LOCAL_STORE_PASSWORD
  chmod 644 "$PKI/"*.crt "$PKI/"*.p12
  # La cle de l'autorite ne quitte pas l'hote ; seules les cles de workload sont lisibles.
  for service in localhost souscription-gateway souscription-service souscription-catalogue-service souscription-simulation-service; do
    chmod 644 "$PKI/$service.key"
  done
  chmod 600 "$PKI/ca.key"
  rm -f "$PKI/"*.csr "$PKI/leaf.conf" "$PKI/ca.conf"
  touch "$PKI/.complete"
  unset LOCAL_STORE_PASSWORD
}

check_journey() {
  command -v curl >/dev/null || fail 'curl est requis.'
  printf '\nVerification HTTPS du web...\n'
  curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    --cacert "$PKI/ca.crt" "$BASE_URL/healthz" >/dev/null
  curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    --cacert "$PKI/ca.crt" "$BASE_URL/" | \
    { content="$(cat)"; [[ "$content" == *'<app-root'* ]] || fail 'La page Angular est absente.'; }
  # Reutilisation du parcours HTTP officiel : CSRF, brouillon, simulation, confirmation.
  # Le certificat est verifie, sans --insecure ; curl transmet ainsi le cookie Secure.
  CURL_CA_BUNDLE="$PKI/ca.crt" BASE_URL="$BASE_URL" \
    bash "$ROOT/souscription-gateway/src/test/scripts/journey-check.sh"
  printf '\nPile operationnelle : %s\n' "$BASE_URL"
}

case "$ACTION" in
  up)
    public_images_config
    generate_pki
    trap diagnose ERR
    compose config --quiet
    if ! $NO_BUILD; then
      printf '\nConstruction des applications reelles (Java 21 et Node dans Docker)...\n'
      compose build
    fi
    printf '\nDemarrage et attente des sondes de disponibilite...\n'
    compose up -d --no-build --force-recreate --wait --wait-timeout 240
    check_journey
    printf 'Navigateur : accepter le certificat local ou faire confiance a .horizon/pki/ca.crt.\n'
    printf 'Journaux : ./scripts/start-all.sh logs | Arret : ./scripts/start-all.sh down\n'
    ;;
  check) check_journey ;;
  status) compose ps --all ;;
  logs) if [[ -n "${2:-}" ]]; then compose logs -f --tail=100 "$2"; else compose logs -f --tail=100; fi ;;
  down) compose down ;;
esac
