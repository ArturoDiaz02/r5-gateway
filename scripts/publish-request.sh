#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || fail "usage: $0 REFERENCE [seconds_in_the_past]  (e.g. $0 SOAT-000001-OK)"
publish_request "$1" "${2:-0}"
echo "OK request $1 published to emission.requests"
