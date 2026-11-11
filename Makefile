.PHONY: help local-up local-down local-reset local-verify logs e2e load failover test ci

help:
	@echo "make local-up     starts everything (Postgres, RabbitMQ, simulator and 2 gateways) and waits until healthy"
	@echo "make e2e          runs the end-to-end scenarios against the local environment"
	@echo "make load         sends 10 requests/s for 60 s (RPS=.. SECONDS=.. to change it)"
	@echo "make failover     kills a gateway mid-flow and checks nothing is lost or duplicated"
	@echo "make logs         follows the gateway and simulator logs"
	@echo "make local-verify checks Postgres, RabbitMQ, queues and delayed messages"
	@echo "make local-down   stops everything"
	@echo "make local-reset  stops, deletes the data and starts again"
	@echo "make ci           unit, integration, coverage and mutation tests (needs JDK 21)"

local-up:
	docker compose up -d --build
	./scripts/wait-gateway.sh

local-down:
	docker compose down

local-reset:
	docker compose down -v
	$(MAKE) local-up

local-verify:
	./scripts/verify-local.sh

logs:
	docker compose logs -f gateway simulator

e2e:
	./scripts/e2e-test.sh

load:
	./scripts/load-test.sh $(or $(RPS),10) $(or $(SECONDS),60)

failover:
	./scripts/failover-test.sh

test:
	cd gateway && ./mvnw -B test

ci:
	cd gateway && ./mvnw -B clean verify
	cd simulador-proveedor && ../gateway/mvnw -B clean verify
