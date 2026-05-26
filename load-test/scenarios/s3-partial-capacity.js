// S3 — partial capacity: 정원 5, 500 동시, latency none.
// 기대: 201 = 5건, 409 = 495건, 503/5xx = 0건, p99 < 1s.
// 정원이 1보다 큰 환경에서도 이중 방어가 정확히 N건만 통과시키는지 검증.
//
// VU=500 + iter=500 → VU당 1 iter. user_id 분산이 단순해진다(VU == user_id offset).
// 사용자 시드는 500명까지 미리 INSERT (seed-users.sql).

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
//   503: LOCK_ACQUISITION_FAILED — Redisson tryLock 대기 만료
const status201 = new Counter('status_201');
const status400 = new Counter('status_400');
const status409 = new Counter('status_409');
const status503 = new Counter('status_503');
const statusOther5xx = new Counter('status_other_5xx');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 500,
      iterations: 500,
      maxDuration: '60s',
    },
  },
  thresholds: {
    'status_other_5xx': ['count==0'],
    'status_201':       ['count<=5'],            // 응답 측 정원 가드. 최종 판정은 check-db-state.sh
    'http_req_duration': ['p(99)<1000'],
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
  if (body.capacity < 5) {
    throw new Error(`setup: class ${CLASS_ID} capacity=${body.capacity} (must be >=5)`);
  }
  return { classId: Number(CLASS_ID) };
}

export default function (data) {
  // VU=500이라 1..500. user_id 2~501에 1:1 매핑 — 동일 user 중복 신청 0.
  const userId = __VU + 1;
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
