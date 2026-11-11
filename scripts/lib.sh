#!/usr/bin/env bash
set -euo pipefail

RABBIT_API="http://localhost:15672/api"
RABBIT_CREDENTIALS="soat:soat"
SIMULATOR_URL="http://localhost:8090"
GATEWAY_URL="http://localhost:8081"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

sql() {
  docker compose exec -T postgres psql -U soat -d soat -At -F '|' -c "$1"
}

iso_date() {
  jq -nr --argjson epoch "$1" '$epoch | todate'
}

publish_request() {
  local reference="$1" offset="${2:-0}"
  local requested_at
  requested_at=$(iso_date $(( $(date +%s) - offset )))
  local body
  body=$(jq -nc --arg ref "$reference" --arg date "$requested_at" \
    '{referencia:$ref, correlation_id:("corr-" + $ref), solicitado_en:$date, prima_cotizada_centavos:89130000, placa:"ABC123"}')
  jq -nc --arg payload "$body" \
    '{properties:{content_type:"application/json", delivery_mode:2}, routing_key:"requests", payload:$payload, payload_encoding:"string"}' \
    | curl -sf -u "$RABBIT_CREDENTIALS" -H 'content-type: application/json' -X POST \
        --data @- "$RABBIT_API/exchanges/%2F/emission/publish" >/dev/null
}

status_of() {
  sql "SELECT status FROM emission WHERE reference = '$1'"
}

wait_for_status() {
  local reference="$1" expected="$2" limit="${3:-60}" start
  start=$(date +%s)
  until [[ "$(status_of "$reference")" == "$expected" ]]; do
    (( $(date +%s) - start < limit )) || fail "$reference did not reach $expected within ${limit}s (status: $(status_of "$reference"))"
    sleep 1
  done
}

published_results() {
  sql "SELECT sequence, result_type, coalesce(reason, '-'), definitive, coalesce(corrects_sequence::text, '-')
       FROM outbox WHERE reference = '$1' AND published_at IS NOT NULL ORDER BY sequence" | tr '\n' ';'
}
