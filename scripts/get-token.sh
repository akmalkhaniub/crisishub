#!/usr/bin/env bash
# Prints an access token for a seeded demo user (DEMO ONLY: uses the ir-cli password-grant client).
#   TOKEN=$(scripts/get-token.sh op1)
set -euo pipefail
USER_NAME="${1:?usage: get-token.sh <member1|member2|op1|resp1|resp2|aud1|admin> [password]}"
PASSWORD="${2:-demo1234}"
KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8180}"

curl -sf -X POST "$KEYCLOAK/realms/incident-response/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=ir-cli -d scope=openid \
  --data-urlencode "username=$USER_NAME" --data-urlencode "password=$PASSWORD" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
