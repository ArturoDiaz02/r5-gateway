#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

LIMIT=${LIMIT_SECONDS:-240}
start=$(date +%s)
for port in 8081 8082; do
  until curl -sf "http://localhost:$port/actuator/health" | grep -q '"status":"UP"'; do
    (( $(date +%s) - start < LIMIT )) || fail "gateway on port $port was not healthy within ${LIMIT}s (see: docker compose logs gateway)"
    sleep 2
  done
  echo "OK gateway healthy at http://localhost:$port"
done
echo "Ready. RabbitMQ console: http://localhost:15672 (soat/soat). Simulator: $SIMULATOR_URL"
