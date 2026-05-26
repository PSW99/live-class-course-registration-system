# 부하 테스트 실행 가이드 (이슈 #15)

본 디렉토리는 동시성 안전(이중 방어 + partial unique index)이 운영 부하에서 정확성·풀 고갈 회피·latency를 만족하는지 외부 부하 테스트로 정량화하는 인프라다.

평상시 `docker compose up`은 영향을 받지 않는다. 부하 테스트 서비스(`toxiproxy`, `app-load`, `k6`)는 모두 Compose profile `load-test`로 격리되어 있다.

## 사전 조건

- Docker Desktop 실행 (Compose v2)
- 호스트 포트 가용: 5432, 5433, 6379, 8080, 8474
- 평상시 `docker compose up`이 8080을 점유 중이면 먼저 내릴 것:
  ```bash
  docker compose down
  ```

## 1회 셋업 (인프라 기동 + 사용자 시드)

```bash
# 부하 모드 기동 — postgres, redis, toxiproxy, app-load 띄움 (k6는 run --rm으로 일회 호출)
docker compose --profile load-test up -d --build

# app-load health 대기 (필요 시 sleep)
until curl -fsS http://localhost:8080/actuator/health | grep -q '"UP"'; do sleep 1; done

# 사용자 시드 (멱등) — id 1: creator, id 2~501: learner
docker compose exec -T postgres psql -U course -d course_registration \
  < load-test/seed/seed-users.sql

# Toxiproxy proxy 자동 등록 확인 — pg proxy enabled=true
curl -s http://localhost:8474/proxies | python3 -m json.tool
```

## 시나리오 실행

### S1 — baseline (정원 1, 100 동시, latency none)

```bash
CID=$(./load-test/seed/seed-class.sh 1)
./load-test/verify/poll-hikari.sh load-test/results/s1-hikari.jsonl &
HIKARI_PID=$!
docker compose --profile load-test run --rm -T \
  -e CLASS_ID=$CID \
  k6 run --summary-export=/results/s1-summary.json /scripts/s1-baseline.js
kill $HIKARI_PID 2>/dev/null || true
./load-test/verify/check-db-state.sh $CID 1 | tee load-test/results/s1-db-state.txt
```

### S2 — DB latency 주입 (정원 1, 100 동시, +200ms ± 50ms)

```bash
CID=$(./load-test/seed/seed-class.sh 1)
./load-test/toxiproxy/inject-latency.sh add
./load-test/verify/poll-hikari.sh load-test/results/s2-hikari.jsonl &
HIKARI_PID=$!
docker compose --profile load-test run --rm -T \
  -e CLASS_ID=$CID \
  k6 run --summary-export=/results/s2-summary.json /scripts/s2-with-latency.js
kill $HIKARI_PID 2>/dev/null || true
./load-test/toxiproxy/inject-latency.sh remove
./load-test/verify/check-db-state.sh $CID 1 | tee load-test/results/s2-db-state.txt
```

> `inject-latency.sh remove`는 S2 종료 후 **항상** 호출. 다음 시나리오 결과 오염 방지.

### S3 — partial capacity (정원 5, 500 동시, latency none)

```bash
CID=$(./load-test/seed/seed-class.sh 5)
./load-test/verify/poll-hikari.sh load-test/results/s3-hikari.jsonl &
HIKARI_PID=$!
docker compose --profile load-test run --rm -T \
  -e CLASS_ID=$CID \
  k6 run --summary-export=/results/s3-summary.json /scripts/s3-partial-capacity.js
kill $HIKARI_PID 2>/dev/null || true
./load-test/verify/check-db-state.sh $CID 5 | tee load-test/results/s3-db-state.txt
```

## 정리

```bash
docker compose --profile load-test down
```

## 결과물 위치

- k6 summary (응답 분포 · latency 분위수): `load-test/results/sN-summary.json`
- HikariCP 1초 폴링 시계열: `load-test/results/sN-hikari.jsonl`
- DB 정합성 검증 출력: `load-test/results/sN-db-state.txt`
- 평가 보고서: `docs/load-test.md`

## 주의 사항

1. **평상시 `app`(8080)과 부하용 `app-load`(8080)은 동시 기동 불가** — 호스트 포트 충돌. 부하 테스트 전 `docker compose down`.
2. **S2 종료 후 `inject-latency.sh remove` 필수** — 잊으면 다음 시나리오에 latency 누출.
3. **사용자 시드 누락 시 신청이 4xx로 떨어진다** — 매 부하 모드 기동 후 1회 `seed-users.sql` 실행.
4. **k6 thresholds는 보조 가드** — 최종 정합성 판정은 `check-db-state.sh`.
5. **503은 정상 거부**(LOCK_ACQUISITION_FAILED) — 5xx 전체 fail로 판단하지 말 것. 시나리오 스크립트는 503을 별도 counter로 추적한다.
