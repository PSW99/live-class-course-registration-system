// S1 — baseline: 정원 1, 100 동시, latency none.
// 기대: 201 = 1건, 409 = 99건, 503/5xx = 0건, p99 < 1s.
//
// 환경 변수:
//   BASE_URL  : http://app-load:8080 (compose가 주입)
//   CLASS_ID  : seed-class.sh로 생성된 강의 ID (호출 시 -e CLASS_ID=... 로 주입)

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
// 5xx 전체를 fail로 잡지 않는다 — 503은 정상 거부 경로다. 503 이외의 5xx만 위험 신호.
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
      maxDuration: '30s',
    },
  },
  thresholds: {
    'status_other_5xx': ['count==0'],     // 503 이외의 5xx는 즉시 fail (커넥션 풀 고갈 등)
    'status_201':       ['count<=1'],     // 응답 측 정원 가드 — 최종 판정은 check-db-state.sh
    'http_req_duration': ['p(99)<1000'],  // 클라이언트 관점 응답 분위수 (연결 setup 포함)
  },
};

export function setup() {
  // 시드 강의 OPEN 상태 검증 — 잘못된 setup으로 시나리오가 의미를 잃지 않도록 가드.
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
  // user 분산 — 같은 user가 두 번 신청하면 409(DUPLICATE)가 capacity 거부와 섞임. VU별 다른 user 사용.
  const userId = (__VU % 500) + 2;
  const res = http.post(
    `${BASE_URL}/api/enrollments`,
    JSON.stringify({ classId: data.classId }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': String(userId) } }
  );

  if (res.status === 201) { status201.add(1); }
  else if (res.status === 400) { status400.add(1); }     // CLASS_NOT_OPEN (BR-06)
  else if (res.status === 409) { status409.add(1); }     // DUPLICATE / CAPACITY_EXCEEDED
  else if (res.status === 503) { status503.add(1); }     // LOCK_ACQUISITION_FAILED
  else if (res.status >= 500) { statusOther5xx.add(1); }

  check(res, {
    'expected status (201/400/409/503)': (r) => [201, 400, 409, 503].includes(r.status),
    'no unexpected 5xx':                 (r) => r.status < 500 || r.status === 503,
  });
}
