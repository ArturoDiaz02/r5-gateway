#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

RPS="${1:-10}"
SECONDS_TO_RUN="${2:-60}"
PREFIX="LOAD$(date +%s)"
TOTAL=$(( RPS * SECONDS_TO_RUN ))

echo "Publishing $TOTAL requests at $RPS per second for ${SECONDS_TO_RUN}s..."
for (( s = 0; s < SECONDS_TO_RUN; s++ )); do
  start=$(date +%s)
  for (( i = 0; i < RPS; i++ )); do
    publish_request "$PREFIX-$(( s * RPS + i ))-OK" &
  done
  wait
  while [[ "$(date +%s)" == "$start" ]]; do sleep 0.1; done
done

echo "Waiting until every request is ISSUED..."
limit=$(( $(date +%s) + 180 ))
while true; do
  issued=$(sql "SELECT count(*) FROM emission WHERE reference LIKE '$PREFIX-%' AND status = 'ISSUED'")
  [[ "$issued" == "$TOTAL" ]] && break
  (( $(date +%s) < limit )) || fail "only $issued of $TOTAL reached ISSUED"
  sleep 2
done
published=$(sql "SELECT count(*) FROM outbox WHERE reference LIKE '$PREFIX-%' AND published_at IS NOT NULL")
[[ "$published" == "$TOTAL" ]] || fail "$published results were published for $TOTAL requests"
echo "OK $TOTAL issued, $published results published, one per reference"
