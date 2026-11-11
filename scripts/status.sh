#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

[[ $# -eq 1 ]] || fail "usage: $0 REFERENCE"
echo "emission:"
sql "SELECT reference, status, policy_number, last_sequence, last_provisional, requested_at
     FROM emission WHERE reference = '$1'"
echo "results (outbox):"
sql "SELECT sequence, result_type, reason, definitive, corrects_sequence, policy_number, published_at
     FROM outbox WHERE reference = '$1' ORDER BY sequence"
echo "scheduled tasks:"
sql "SELECT task_name, scheduled_for FROM scheduled_task WHERE reference = '$1' ORDER BY created_at"
