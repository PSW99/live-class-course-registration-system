#!/usr/bin/env bash
# toxiproxy의 'pg' proxy에 latency toxic을 add/remove 한다.
# 사용:
#   ./inject-latency.sh add        # 200ms ± 50ms downstream latency 주입
#   ./inject-latency.sh remove     # toxic 제거
#
# 호스트에서 직접 호출 (toxiproxy 호스트 8474 포트 노출 가정).

set -euo pipefail

TOXIPROXY_URL="${TOXIPROXY_URL:-http://localhost:8474}"
PROXY_NAME="pg"
TOXIC_NAME="latency_downstream"

action="${1:-}"
case "${action}" in
  add)
    echo "[inject-latency] adding ${TOXIC_NAME} (latency=200ms, jitter=50ms) to ${PROXY_NAME}"
    curl -fsS -X POST "${TOXIPROXY_URL}/proxies/${PROXY_NAME}/toxics" \
      -H 'Content-Type: application/json' \
      -d "{
        \"name\": \"${TOXIC_NAME}\",
        \"type\": \"latency\",
        \"stream\": \"downstream\",
        \"toxicity\": 1.0,
        \"attributes\": { \"latency\": 200, \"jitter\": 50 }
      }" >/dev/null
    echo "[inject-latency] added"
    ;;
  remove)
    echo "[inject-latency] removing ${TOXIC_NAME} from ${PROXY_NAME}"
    # 멱등: 없으면 404. 무시.
    curl -fsS -X DELETE "${TOXIPROXY_URL}/proxies/${PROXY_NAME}/toxics/${TOXIC_NAME}" >/dev/null 2>&1 || true
    echo "[inject-latency] removed (or already absent)"
    ;;
  list)
    curl -fsS "${TOXIPROXY_URL}/proxies/${PROXY_NAME}/toxics"
    echo
    ;;
  *)
    echo "usage: $0 {add|remove|list}" >&2
    exit 2
    ;;
esac
