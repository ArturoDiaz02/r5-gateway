#!/usr/bin/env bash
set -euo pipefail

RABBIT_API="http://localhost:15672/api"
RABBIT_CREDENTIALS="soat:soat"
LIMIT_SECONDS=${LIMIT_SECONDS:-90}
QUEUES=(emission.requests emission.requests.dlq emission.status-checks emission.expirations emission.results)
TEST_QUEUE="verification.delayed"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

api() {
  curl -sf -u "$RABBIT_CREDENTIALS" -H 'content-type: application/json' "$@"
}

start=$(date +%s)
until docker compose exec -T postgres pg_isready -U soat -d soat >/dev/null 2>&1 && api "$RABBIT_API/overview" >/dev/null 2>&1; do
  (( $(date +%s) - start < LIMIT_SECONDS )) || fail "Postgres or RabbitMQ did not answer within ${LIMIT_SECONDS}s"
  sleep 2
done
echo "OK Postgres and RabbitMQ answer in $(( $(date +%s) - start ))s"

api "$RABBIT_API/exchanges/%2F/emission.delayed" | grep -q '"type":"x-delayed-message"' \
  || fail "exchange emission.delayed is not x-delayed-message"
echo "OK exchange emission.delayed is x-delayed-message"

for queue in "${QUEUES[@]}"; do
  api "$RABBIT_API/queues/%2F/$queue" >/dev/null || fail "queue $queue does not exist"
done
echo "OK queues: ${QUEUES[*]}"

api -X PUT -d '{"durable":false,"auto_delete":false}' "$RABBIT_API/queues/%2F/$TEST_QUEUE" >/dev/null
trap 'api -X DELETE "$RABBIT_API/queues/%2F/$TEST_QUEUE" >/dev/null 2>&1 || true' EXIT
api -X POST -d '{"routing_key":"verification"}' "$RABBIT_API/bindings/%2F/e/emission.delayed/q/$TEST_QUEUE" >/dev/null
api -X POST "$RABBIT_API/exchanges/%2F/emission.delayed/publish" \
  -d '{"properties":{"headers":{"x-delay":2000}},"routing_key":"verification","payload":"verification","payload_encoding":"string"}' \
  >/dev/null || fail "could not publish the delayed message"

take_message() {
  api -X POST "$RABBIT_API/queues/%2F/$TEST_QUEUE/get" -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}'
}
sleep 1
take_message | grep -q '"payload":"verification"' && fail "the message arrived before the delay"
waited=0
until take_message | grep -q '"payload":"verification"'; do
  (( waited < 10 )) || fail "the delayed message never arrived"
  sleep 1
  waited=$((waited + 1))
done
echo "OK 2 s delayed message arrived on time (temporary queue $TEST_QUEUE, already deleted)"
