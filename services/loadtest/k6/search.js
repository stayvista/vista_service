import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    search_steady: {
      executor: "constant-arrival-rate",
      rate: 200,
      timeUnit: "1s",
      duration: "10m",
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<200", "p(99)<500"],
    http_req_failed: ["rate<0.01"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:18765";

export default function () {
  const res = http.get(`${BASE}/v1/search/properties?city=Seoul&limit=20`);
  check(res, {
    "status is 200": (r) => r.status === 200,
  });
  sleep(0.1);
}
