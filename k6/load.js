// Grafana k6 load test for the showcase app.
//
// Fires a weighted mix of good and failing requests (like the old shell loadgen,
// but with proper checks, thresholds and k6 metrics). Runs "forever" by default;
// tune via env: TARGET, RATE (req/s), DURATION.
import http from 'k6/http';
import { check } from 'k6';

const TARGET = __ENV.TARGET || 'http://app:8080';
const RATE = Number(__ENV.RATE || 3);          // requests per second
const DURATION = __ENV.DURATION || '24h';      // service restarts when it ends

export const options = {
  scenarios: {
    traffic: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
  },
  thresholds: {
    // The mix below deliberately produces ~25% failures; alert only above that.
    http_req_failed: ['rate<0.40'],
    'http_req_duration{expected_response:true}': ['p(95)<1000'],
  },
};

// Weighted endpoint mix: ~60% success, ~25% deliberate failures (404/500), rest flaky.
const MIX = [
  { weight: 30, path: '/api/hello?name=k6', expect: 200 },
  { weight: 20, path: '/api/work', expect: 200 },
  { weight: 10, path: '/', expect: 200 },
  { weight: 20, path: '/api/flaky', expect: [200, 500] }, // ~1/3 of these fail
  { weight: 10, path: '/does-not-exist', expect: 404 },
  { weight: 10, path: '/api/boom', expect: 500 },
];
const TOTAL_WEIGHT = MIX.reduce((s, m) => s + m.weight, 0);

function pick() {
  let r = Math.random() * TOTAL_WEIGHT;
  for (const m of MIX) {
    if ((r -= m.weight) < 0) return m;
  }
  return MIX[0];
}

export default function () {
  const m = pick();
  const res = http.get(`${TARGET}${m.path}`, {
    tags: { endpoint: m.path.split('?')[0] },
  });
  const expected = Array.isArray(m.expect) ? m.expect : [m.expect];
  check(res, {
    'status is expected': (r) => expected.includes(r.status),
  });
}
