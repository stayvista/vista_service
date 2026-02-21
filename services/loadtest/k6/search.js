import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

export const options = {
  scenarios: {
    search_with_filters: {
      executor: "constant-arrival-rate",
      rate: 100,
      timeUnit: "1s",
      duration: "8m",
      preAllocatedVUs: 80,
      maxVUs: 260,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.02"],
    search_req_duration_ms: ["p(95)<350", "p(99)<900"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:18765";
const durationTrend = new Trend("search_req_duration_ms");
const errorRate = new Rate("search_5xx_rate");

const queries = [
  "city=Seoul&check_in=2026-03-10&check_out=2026-03-12&rooms=1&adults=2&children=0&currency=KRW&sort=best_match&size=20&stars=5,4&amenities=wifi,breakfast",
  "city=Seoul&check_in=2026-03-10&check_out=2026-03-12&rooms=1&adults=2&children=1&children_ages=7&currency=USD&sort=price_asc&size=20&property_type=hotel&themes=family,city_break",
  "city=Busan&check_in=2026-03-10&check_out=2026-03-12&rooms=1&adults=2&children=0&currency=KRW&sort=rating_desc&size=20&districts=해운대구&brands=1,2",
  "city=Jeju&check_in=2026-03-10&check_out=2026-03-12&rooms=1&adults=2&children=0&currency=JPY&sort=distance&size=20&payment_options=pay_now&min_rating=4.2&max_distance_m=8000",
];

export default function () {
  const query = queries[Math.floor(Math.random() * queries.length)];
  const res = http.get(`${BASE}/v1/search/properties?${query}`);

  durationTrend.add(res.timings.duration);
  errorRate.add(res.status >= 500);

  check(res, {
    "search status is 200": (r) => r.status === 200,
  });

  sleep(0.05);
}
