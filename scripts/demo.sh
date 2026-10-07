#!/usr/bin/env bash
# End-to-end demo against `docker compose up`: full case lifecycle, ownership checks, audit trail.
set -euo pipefail
cd "$(dirname "$0")"
CASES="${CASES_URL:-http://localhost:8081}"
AUDIT="${AUDIT_URL:-http://localhost:8084}"

MEMBER=$(./get-token.sh member1); MEMBER2=$(./get-token.sh member2)
OP=$(./get-token.sh op1); RESP=$(./get-token.sh resp1); AUD=$(./get-token.sh aud1)
json() { sed -E "s/.*\"$1\":\"?([^\",}]+).*/\1/"; }
call() { local tok=$1 method=$2 path=$3 body=${4:-}; shift 3
  curl -s -o /tmp/ir-body -w "%{http_code}" -X "$method" "$CASES$path" \
    -H "Authorization: Bearer $tok" -H "Content-Type: application/json" ${body:+-d "$body"}; }

echo "== member1 reports a case"
ID=$(curl -sf -X POST "$CASES/api/cases" -H "Authorization: Bearer $MEMBER" -H "Content-Type: application/json" \
  -d '{"type":"MEDICAL","title":"Hiker with leg injury","lat":46.55,"lng":7.98,"reportedSeverity":"HIGH"}' | json id)
echo "   case id: $ID"

echo "== member1 tries to triage (expect 403): $(call "$MEMBER" POST "/api/cases/$ID/triage" '{"severity":"HIGH"}')"
echo "== member2 reads member1's case (expect 404): $(call "$MEMBER2" GET "/api/cases/$ID")"
echo "== op1 triages (expect 200): $(call "$OP" POST "/api/cases/$ID/triage" '{"severity":"HIGH"}')"
echo "== op1 closes too early (expect 409): $(call "$OP" POST "/api/cases/$ID/close")"
echo "== op1 assigns best responder (expect 200): $(call "$OP" POST "/api/cases/$ID/assign" '{}')"
echo "== resp1 resolves (expect 200): $(call "$RESP" POST "/api/cases/$ID/resolve" '{"note":"Evacuated to Interlaken"}')"
echo "== op1 closes (expect 200): $(call "$OP" POST "/api/cases/$ID/close")"

echo "== timeline (member1)"
curl -sf "$CASES/api/cases/$ID/timeline" -H "Authorization: Bearer $MEMBER"; echo

echo "== waiting for audit trail..."; sleep 3
echo "== audit trail (aud1)"
curl -sf "$AUDIT/api/audit?entityId=$ID" -H "Authorization: Bearer $AUD"; echo
echo "== operator reading audit (expect 403): $(curl -s -o /dev/null -w "%{http_code}" "$AUDIT/api/audit?entityId=$ID" -H "Authorization: Bearer $OP")"
