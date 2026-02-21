import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

export const options = {
  scenarios: {
    price_calendar_steady: {
      executor: "constant-arrival-rate",
      rate: 50,
      timeUnit: "1s",
      duration: "6m",
      preAllocatedVUs: 40,
      maxVUs: 160,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.02"],
    price_calendar_req_duration_ms: ["p(95)<220", "p(99)<600"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:18765";
const durationTrend = new Trend("price_calendar_req_duration_ms");
const tooManyRequestsRate = new Rate("price_calendar_429_rate");

const places = ["city:Seoul", "city:Busan", "city:Jeju"];
const currencies = ["KRW", "USD", "JPY", "EUR"];

export default function () {
  const placeId = places[Math.floor(Math.random() * places.length)];
  const currency = currencies[Math.floor(Math.random() * currencies.length)];

  const res = http.get(
    `${BASE}/v1/prices/calendar?place_id=${encodeURIComponent(placeId)}&from=2026-03-01&to=2026-04-30&currency=${currency}&rooms=1&adults=2&children=0`,
  );

  durationTrend.add(res.timings.duration);
  tooManyRequestsRate.add(res.status === 429);

  check(res, {
    "calendar status is 200": (r) => r.status === 200,
  });

  sleep(0.08);
}
