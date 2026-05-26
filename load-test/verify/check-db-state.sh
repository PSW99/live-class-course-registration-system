#!/usr/bin/env bash
# 부하 테스트 종료 후 DB 정합성 검증.
# 사용:
#   ./check-db-state.sh <class_id> <expected_success_count>
#
# 검증 대상:
#   1) classes.current_count == expected_success_count
#   2) 활성 enrollment row 수(status <> 'CANCELLED') == expected_success_count
#   3) capacity == expected이면 status == 'CLOSED' (BR-06)
# 불일치 시 exit 1, 검증 결과는 stdout으로 출력.

set -euo pipefail

CLASS_ID="${1:?class_id 인자 필수}"
EXPECTED="${2:?expected_success_count 인자 필수}"

# docker compose exec로 postgres 컨테이너의 psql 호출 — tab-separated raw output.
ROW=$(docker compose exec -T postgres psql -U course -d course_registration -tA -F'|' -c "
  SELECT
    c.capacity,
    c.current_count,
    c.status,
    (SELECT COUNT(*) FROM enrollments e WHERE e.class_id = c.id AND e.status <> 'CANCELLED')
  FROM classes c WHERE c.id = ${CLASS_ID};
")

IFS='|' read -r CAPACITY CURRENT_COUNT STATUS ACTIVE_ENROLLMENTS <<< "${ROW}"

echo "=== DB 정합성 검증 (class_id=${CLASS_ID}, expected=${EXPECTED}) ==="
echo "capacity            : ${CAPACITY}"
echo "current_count       : ${CURRENT_COUNT}"
echo "status              : ${STATUS}"
echo "active enrollments  : ${ACTIVE_ENROLLMENTS}"

mismatch=0

if [[ "${CURRENT_COUNT}" != "${EXPECTED}" ]]; then
  echo "FAIL: current_count(${CURRENT_COUNT}) != expected(${EXPECTED})"
  mismatch=1
fi

if [[ "${ACTIVE_ENROLLMENTS}" != "${EXPECTED}" ]]; then
  echo "FAIL: active enrollments(${ACTIVE_ENROLLMENTS}) != expected(${EXPECTED})"
  mismatch=1
fi

if [[ "${CAPACITY}" == "${EXPECTED}" && "${STATUS}" != "CLOSED" ]]; then
  echo "FAIL: capacity == expected 인데 status != CLOSED (got=${STATUS})"
  mismatch=1
fi

if [[ "${mismatch}" -eq 0 ]]; then
  echo "PASS: 모든 검증 통과"
  exit 0
else
  echo "FAIL: 검증 실패 (위 항목 확인)"
  exit 1
fi
