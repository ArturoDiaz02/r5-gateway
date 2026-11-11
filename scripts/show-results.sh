#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

curl -sf -u "$RABBIT_CREDENTIALS" -H 'content-type: application/json' -X POST \
  -d '{"count":100,"ackmode":"ack_requeue_true","encoding":"auto"}' \
  "$RABBIT_API/queues/%2F/emission.results/get" | jq -c '.[].payload | fromjson'
