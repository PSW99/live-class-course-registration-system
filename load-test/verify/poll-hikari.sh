#!/usr/bin/env bash
# Spring Actuator로 HikariCP 메트릭을 1초 간격으로 polling → JSONL append.
# 사용:
#   ./poll-hikari.sh <output_file.jsonl> [base_url]
#   (background로 실행 후 시나리오 종료 시 kill)
#
# Spring Boot 3.x는 hikaricp.connections.active / .pending / .idle / .max 를 자동 노출.
# application.yml의 management.endpoints.web.exposure.include 에 metrics 포함 필요.

set -euo pipefail

OUT_FILE="${1:?output file 인자 필수: ./poll-hikari.sh <file> [base_url]}"
BASE_URL="${2:-http://localhost:8080}"

# 빈 파일로 초기화 (append 모드 — 호출자가 회당 새 파일 지정)
: > "${OUT_FILE}"

# SIGTERM·SIGINT 수신 시 깨끗하게 종료
trap 'exit 0' TERM INT

metric_value() {
  # /actuator/metrics/<name> → measurements[0].value
  # 없는 메트릭은 0으로 처리.
  curl -fsS "${BASE_URL}/actuator/metrics/$1" 2>/dev/null \
    | python3 -c 'import sys,json;
try:
  d=json.load(sys.stdin); print(d["measurements"][0]["value"])
except Exception:
  print(0)' || echo 0
}

while true; do
  TS=$(date -u +%FT%TZ)
  ACTIVE=$(metric_value 'hikaricp.connections.active')
  PENDING=$(metric_value 'hikaricp.connections.pending')
  IDLE=$(metric_value 'hikaricp.connections.idle')
  TOTAL=$(metric_value 'hikaricp.connections')
  printf '{"ts":"%s","active":%s,"pending":%s,"idle":%s,"total":%s}\n' \
    "${TS}" "${ACTIVE}" "${PENDING}" "${IDLE}" "${TOTAL}" >> "${OUT_FILE}"
  sleep 1
done
