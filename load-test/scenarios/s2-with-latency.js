// S2 — DB latency 주입 환경: 정원 1, 100 동시, +200ms ± 50ms (Toxiproxy downstream toxic).
// 기대: 201 = 1건, 정원 정합성 유지, 503(LOCK_ACQUISITION_FAILED) 일부 허용, 다른 5xx = 0, p99 < 5s.
// 핵심 검증: 레이턴시 주입으로 락 경합이 표면화되어도 HikariCP 풀이 고갈되지 않는다.
//
// toxic add/remove는 호스트 wrapper(README의 inject-latency.sh add/remove)가 담당한다.
// k6 setup/teardown에서 직접 호출하지 않는 이유: 시나리오 실행 단위가 wrapper이며, k6는 부하만 담당.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app-load:8080';
const CLASS_ID = __ENV.CLASS_ID;
if (!CLASS_ID) { throw new Error('CLASS_ID env required (use -e CLASS_ID=...)'); }

// 응답 분포 — 의도된 거부 응답을 status별로 추적.
//   201: 신청 성공
//   400: CLASS_NOT_OPEN — 정원이 다 차 CLOSED로 전환된 후 도착한 요청 (BR-06)
//   409: DUPLICATE_ENROLLMENT / CAPACITY_EXCEEDED 등 도메인 경합
//   503: LOCK_ACQUISITION_FAILED — Redisson tryLock 대기 만료 (S2의 핵심 관찰 대상)
const status201 = new Counter('status_201');
const status400 = new Counter('status_400');
const status409 = new Counter('status_409');
const status503 = new Counter('status_503');
const statusOther5xx = new Counter('status_other_5xx');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 100,
      iterations: 100,
      maxDuration: '90s',                       // latency 주입 — 더 긴 budget
    },
  },
  thresholds: {
    'status_other_5xx': ['count==0'],           // 503 외 5xx는 즉시 fail (커넥션 풀 고갈 가드)
    'status_201':       ['count<=1'],
    'http_req_duration': ['p(99)<5000'],        // latency 주입 환경 — 완화 임계 (p99 < 5s)
  },
};

export function setup() {
  const res = http.get(`${BASE_URL}/api/classes/${CLASS_ID}`);
  if (res.status !== 200) {
    throw new Error(`setup: class ${CLASS_ID} not found (status=${res.status})`);
  }
  const body = res.json();
  if (body.status !== 'OPEN') {
    throw new Error(`setup: class ${CLASS_ID} status=${body.status} (must be OPEN)`);
  }
  return { classId: Number(CLASS_ID) };
}

export default function (data) {
  const userId = (__VU % 500) + 2;
  const res = http.post(
    `${BASE_URL}/api/enrollments`,
    JSON.stringify({ classId: data.classId }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': String(userId) } }
  );

  if (res.status === 201) { status201.add(1); }
  else if (res.status === 400) { status400.add(1); }
  else if (res.status === 409) { status409.add(1); }
  else if (res.status === 503) { status503.add(1); }
  else if (res.status >= 500) { statusOther5xx.add(1); }

  check(res, {
    'expected status (201/400/409/503)': (r) => [201, 400, 409, 503].includes(r.status),
    'no unexpected 5xx':                 (r) => r.status < 500 || r.status === 503,
  });
}
