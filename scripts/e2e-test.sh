#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

RUN="E2E$(date +%s)"
EXPIRES_IN=15
EXPIRATION_OFFSET=$(( 23 * 3600 - EXPIRES_IN ))

echo "Run $RUN: publishing requests..."
for suffix in OK REJECT NOWEBHOOK DUP SLOW 5XX NEVER; do
  publish_request "$RUN-$suffix"
done
publish_request "$RUN-X-NEVER" "$EXPIRATION_OFFSET"

check() {
  local reference="$1" status="$2" results="$3" limit="$4"
  wait_for_status "$reference" "$status" "$limit"
  local published
  published=$(published_results "$reference")
  [[ "$published" == "$results" ]] || fail "$reference: results '$published', expected '$results'"
  echo "OK $reference -> $status ${results:-(no published result)}"
}

check "$RUN-OK"        ISSUED          "1|SUCCESS|-|t|-;" 30
check "$RUN-REJECT"    REJECTED        "1|ERROR|REJECTED|t|-;" 30
check "$RUN-NOWEBHOOK" ISSUED          "1|SUCCESS|-|t|-;" 30
check "$RUN-DUP"       ISSUED          "1|SUCCESS|-|t|-;" 30
check "$RUN-SLOW"      ISSUED          "1|SUCCESS|-|t|-;" 40
check "$RUN-5XX"       SEND_FAILED     "" 90
check "$RUN-NEVER"     REVIEW_REQUIRED "" 90

check "$RUN-X-NEVER"   EXPIRED         "1|ERROR|EXPIRED|f|-;" 60
curl -sf -X POST "$SIMULATOR_URL/simulator/approve/$RUN-X-NEVER" >/dev/null
check "$RUN-X-NEVER"   ISSUED          "1|ERROR|EXPIRED|f|-;2|SUCCESS|-|t|1;" 30

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY_URL/webhooks/pagos" \
  -H 'content-type: application/json' -H 'X-Timestamp: 1' -H "X-Firma: $(printf '0%.0s' {1..64})" -d '{}')
[[ "$code" == "401" ]] || fail "webhook with an invalid signature answered $code"
echo "OK webhook with an invalid signature -> 401"

duplicated=$(sql "SELECT count(*) FROM (SELECT policy_number FROM emission WHERE policy_number IS NOT NULL
                  GROUP BY policy_number HAVING count(*) > 1) d")
[[ "$duplicated" == "0" ]] || fail "there are $duplicated duplicated policies"
echo "OK no duplicated policies"
echo "E2E complete: every scenario passed."
