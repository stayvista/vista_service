import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

export const options = {
  scenarios: {
    hold_spike: {
      executor: "ramping-arrival-rate",
      startRate: 10,
      timeUnit: "1s",
      stages: [
        { target: 100, duration: "30s" },
        { target: 500, duration: "2m" },
        { target: 0, duration: "30s" },
      ],
      preAllocatedVUs: 300,
      maxVUs: 1000,
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<150", "p(99)<300"],
    http_req_failed: ["rate<0.01"],
    hold_5xx_rate: ["rate<0.001"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const ROOM_TYPE_ID = Number(__ENV.ROOM_TYPE_ID || "1");
const CHECK_IN = __ENV.CHECK_IN || "2026-02-10";
const CHECK_OUT = __ENV.CHECK_OUT || "2026-02-12";

const holdDuration = new Trend("hold_duration", true);
const hold409Rate = new Rate("hold_409_rate");
const hold429Rate = new Rate("hold_429_rate");
const hold5xxRate = new Rate("hold_5xx_rate");

export default function () {
  const payload = JSON.stringify({
    room_type_id: ROOM_TYPE_ID,
    check_in: CHECK_IN,
    check_out: CHECK_OUT,
    rooms: 1,
    guests: { adults: 2, children: 0 },
    price: { currency: "KRW", amount_total: 120000 },
  });

  const res = http.post(`${BASE}/v1/bookings/holds`, payload, {
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
      "X-User-Id": String(__VU * 100000 + __ITER + 1),
    },
  });

  holdDuration.add(res.timings.duration);
  hold409Rate.add(res.status === 409);
  hold429Rate.add(res.status === 429);
  hold5xxRate.add(res.status >= 500);

  check(res, {
    "hold status is 201 or conflict": (r) => r.status === 201 || r.status === 409 || r.status === 429,
  });
}
