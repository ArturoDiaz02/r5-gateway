#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

moved=0
while true; do
  message=$(curl -sf -u "$RABBIT_CREDENTIALS" -H 'content-type: application/json' -X POST \
    -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}' \
    "$RABBIT_API/queues/%2F/emission.requests.dlq/get" | jq -c '.[0] // empty')
  [[ -n "$message" ]] || break
  echo "$message" | jq -c '{properties:{content_type:"application/json", delivery_mode:2}, routing_key:"requests", payload:.payload, payload_encoding:"string"}' \
    | curl -sf -u "$RABBIT_CREDENTIALS" -H 'content-type: application/json' -X POST \
        --data @- "$RABBIT_API/exchanges/%2F/emission/publish" >/dev/null
  moved=$((moved + 1))
done
echo "OK $moved requests moved from the dead letter queue back to emission.requests"
