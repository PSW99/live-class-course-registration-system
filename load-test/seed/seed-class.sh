#!/usr/bin/env bash
# 부하 테스트 시나리오용 강의 1건 생성 → OPEN 전이 → 새 class_id를 stdout으로 반환.
# 사용:
#   CID=$(./seed-class.sh <capacity> [base_url])
#
# 평가자는 매 시나리오 전 호출해 새 강의를 만든다 (이전 시나리오의 잔여 enrollment 영향 회피).

set -euo pipefail

CAPACITY="${1:?capacity 인자 필수: ./seed-class.sh <capacity> [base_url]}"
BASE_URL="${2:-http://localhost:8080}"

# 강의 등록 — creator_id = 1
CREATE_RES=$(curl -fsS -X POST "${BASE_URL}/api/classes" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1' \
  -d "{
    \"title\": \"load-test-cap-${CAPACITY}-$(date +%s)\",
    \"description\": \"load test class\",
    \"price\": 0,
    \"capacity\": ${CAPACITY},
    \"startDate\": \"2099-01-01\",
    \"endDate\": \"2099-12-31\"
  }")

# python3로 JSON 파싱 — jq 의존 없이 표준 도구만.
CLASS_ID=$(printf '%s' "${CREATE_RES}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# OPEN 상태 전이 (DRAFT → OPEN). 신청 가능 상태로 만든다.
curl -fsS -X PATCH "${BASE_URL}/api/classes/${CLASS_ID}/status" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1' \
  -d '{"status": "OPEN"}' >/dev/null

echo "${CLASS_ID}"
