import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

export const options = {
  scenarios: {
    full_funnel: {
      executor: "constant-vus",
      vus: Number(__ENV.FUNNEL_VUS || "50"),
      duration: __ENV.FUNNEL_DURATION || "10m",
    },
  },
  thresholds: {
    funnel_search_duration: ["p(95)<250"],
    funnel_hold_duration: ["p(95)<300"],
    funnel_confirm_duration: ["p(95)<400"],
    funnel_5xx_rate: ["rate<0.001"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const SEARCH_CITY = __ENV.SEARCH_CITY || "Seoul";

const funnelSearchDuration = new Trend("funnel_search_duration", true);
const funnelHoldDuration = new Trend("funnel_hold_duration", true);
const funnelConfirmDuration = new Trend("funnel_confirm_duration", true);
const funnel409Rate = new Rate("funnel_409_rate");
const funnel429Rate = new Rate("funnel_429_rate");
const funnel5xxRate = new Rate("funnel_5xx_rate");

export default function () {
  const userId = String(__VU * 10000 + __ITER + 1);
  const search = http.get(`${BASE}/v1/search/properties?city=${encodeURIComponent(SEARCH_CITY)}&limit=5`);
  funnelSearchDuration.add(search.timings.duration);
  funnel5xxRate.add(search.status >= 500);
  check(search, { "search ok": (r) => r.status === 200 });

  const hold = http.post(`${BASE}/v1/bookings/holds`, JSON.stringify({
    room_type_id: Number(__ENV.ROOM_TYPE_ID || "1"),
    check_in: "2026-02-10",
    check_out: "2026-02-12",
    rooms: 1,
    guests: { adults: 2, children: 0 },
    price: { currency: "KRW", amount_total: 120000 }
  }), {
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
      "X-User-Id": userId,
    },
  });
  funnelHoldDuration.add(hold.timings.duration);
  funnel409Rate.add(hold.status === 409);
  funnel429Rate.add(hold.status === 429);
  funnel5xxRate.add(hold.status >= 500);

  if (hold.status === 201) {
    const body = JSON.parse(hold.body);
    const bookingId = body.data.booking_id;
    const confirm = http.post(`${BASE}/v1/bookings/${bookingId}/confirm`, JSON.stringify({
      payment_method: "CARD",
      payment_token: "paytok_test",
      agree_terms: true,
    }), {
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": crypto.randomUUID(),
        "X-User-Id": userId,
      },
    });
    funnelConfirmDuration.add(confirm.timings.duration);
    funnel409Rate.add(confirm.status === 409);
    funnel429Rate.add(confirm.status === 429);
    funnel5xxRate.add(confirm.status >= 500);
    check(confirm, { "confirm ok or conflict": (r) => r.status === 200 || r.status === 409 || r.status === 429 });
  }

  sleep(0.2);
}
