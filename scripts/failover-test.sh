#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

PREFIX="FAILOVER$(date +%s)"
TOTAL=40

echo "Publishing $TOTAL SLOW requests (the provider takes 8 s to answer)..."
for (( i = 0; i < TOTAL; i++ )); do
  publish_request "$PREFIX-$i-SLOW" &
done
wait
sleep 3
echo "Killing soat-local-gateway-1 with calls in flight..."
docker kill soat-local-gateway-1 >/dev/null
sleep 5
echo "Starting the instance again..."
docker compose up -d gateway >/dev/null

limit=$(( $(date +%s) + 180 ))
while true; do
  issued=$(sql "SELECT count(*) FROM emission WHERE reference LIKE '$PREFIX-%' AND status = 'ISSUED'")
  [[ "$issued" == "$TOTAL" ]] && break
  (( $(date +%s) < limit )) || fail "only $issued of $TOTAL reached ISSUED after the failure"
  sleep 2
done
results=$(sql "SELECT count(*) FROM outbox WHERE reference LIKE '$PREFIX-%'")
[[ "$results" == "$TOTAL" ]] || fail "there are $results results for $TOTAL emissions (expected one per reference)"
echo "OK after killing an instance: $TOTAL issued and a single result per reference"
