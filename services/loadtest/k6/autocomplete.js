import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

export const options = {
  scenarios: {
    empty_focus: {
      executor: "constant-arrival-rate",
      rate: 50,
      timeUnit: "1s",
      duration: "2m",
      preAllocatedVUs: 25,
      maxVUs: 80,
      exec: "emptyFocus",
    },
    typing_mixed: {
      executor: "constant-arrival-rate",
      rate: 200,
      timeUnit: "1s",
      duration: "5m",
      preAllocatedVUs: 80,
      maxVUs: 260,
      exec: "typingMixed",
      startTime: "15s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.02"],
    ac_req_duration_ms: ["p(95)<200"],
    ac_cache_hit_rate: ["rate>0.30"],
    ac_429_rate: ["rate<0.10"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:18765";

const cacheHitRate = new Rate("ac_cache_hit_rate");
const tooManyRequestsRate = new Rate("ac_429_rate");
const durationTrend = new Trend("ac_req_duration_ms");
const badPayloadCount = new Counter("ac_bad_payload_total");

const typedQueries = ["s", "se", "seo", "seou", "seoul", "부", "부산", "je", "jej", "jeju"];
const queryTypes = "city,property,poi,station,airport";

function callAutocomplete(path) {
  const res = http.get(`${BASE}${path}`, {
    headers: {
      "X-Anon-Id": `k6-${__VU}`,
      "User-Agent": "k6-autocomplete",
    },
  });

  durationTrend.add(res.timings.duration);
  tooManyRequestsRate.add(res.status === 429);

  if (res.status === 200) {
    let parsed;
    try {
      parsed = res.json();
    } catch (error) {
      badPayloadCount.add(1);
      return res;
    }

    const hit = Boolean(parsed?.data?.meta?.cache_hit);
    cacheHitRate.add(hit);
  }

  check(res, {
    "autocomplete status is 200 or 429": (r) => r.status === 200 || r.status === 429,
  });

  return res;
}

export function emptyFocus() {
  callAutocomplete(`/v1/autocomplete?types=${encodeURIComponent(queryTypes)}&size=10&lang=ko`);
  sleep(0.08);
}

export function typingMixed() {
  const q = typedQueries[Math.floor(Math.random() * typedQueries.length)];
  callAutocomplete(`/v1/autocomplete?q=${encodeURIComponent(q)}&types=${encodeURIComponent(queryTypes)}&size=10&lang=ko`);
  sleep(0.05);
}

export default function () {
  typingMixed();
}
