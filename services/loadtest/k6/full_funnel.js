import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  vus: 50,
  duration: "10m",
};

const BASE = __ENV.BASE_URL || "http://localhost:8080";

export default function () {
  const search = http.get(`${BASE}/v1/search/properties?city=Seoul&limit=5`);
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
      "X-User-Id": String(__VU * 10000 + __ITER + 1),
    },
  });

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
        "X-User-Id": String(__VU * 10000 + __ITER + 1),
      },
    });
    check(confirm, { "confirm ok or conflict": (r) => r.status === 200 || r.status === 409 });
  }

  sleep(0.2);
}
