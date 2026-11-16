# Local development entry points of souscription-gateway (W6-T05). See README.md.
#   make dev                  flow (a): quarkus:dev against the contract stubs, no sibling, no container
#   make dev-topology         flow (b): full local topology (ingress + SPA + gateway + 3 services)
#   make dev-topology-check   one complete subscription journey through the ingress
#   make dev-topology-down    stop and remove the topology
#   make stubs                regenerate the WireMock stubs from global/contracts/

PROFILE ?= stubs
COMPOSE ?= $(shell docker compose version >/dev/null 2>&1 && echo "docker compose" || echo docker-compose)
COMPOSE_CMD = $(COMPOSE) -f docker-compose.dev.yml --profile $(PROFILE)
INGRESS_PORT ?= 8088

.PHONY: dev stubs dev-topology dev-topology-check dev-topology-down

dev:
	src/test/scripts/dev-stubs.sh

stubs:
	src/test/scripts/generate-stubs.sh

dev-topology:
	mvn -q package -DskipTests
	CSRF_SIGNING_KEY="$${CSRF_SIGNING_KEY:-dev-$$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')}" \
	  $(COMPOSE_CMD) up -d --build --wait

dev-topology-check:
	BASE_URL=http://127.0.0.1:$(INGRESS_PORT) src/test/scripts/journey-check.sh

dev-topology-down:
	CSRF_SIGNING_KEY=unused $(COMPOSE_CMD) down --remove-orphans
