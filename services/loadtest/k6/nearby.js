import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

const nearbyLatency = new Trend("nearby_req_duration_ms", true);
const nearby429Rate = new Rate("nearby_429_rate");
const nearby5xxRate = new Rate("nearby_5xx_rate");
const nearbyErrorRate = new Rate("nearby_error_rate");

const BASE = __ENV.BASE_URL || "http://localhost:18765";
const LIMIT = __ENV.LIMIT || "120";

const CITY_CENTERS = [
  { lat: 37.501, lng: 127.0396, category: "attraction" },
  { lat: 35.1595, lng: 129.0756, category: "food" },
  { lat: 33.4996, lng: 126.5312, category: "shopping" },
  { lat: 37.4563, lng: 126.7052, category: "museum" },
];

export const options = {
  scenarios: {
    nearby_steady: {
      executor: "constant-arrival-rate",
      rate: 50,
      timeUnit: "1s",
      duration: "10m",
      preAllocatedVUs: 40,
      maxVUs: 150,
      exec: "steady",
    },
    nearby_drag: {
      executor: "constant-vus",
      vus: 20,
      duration: "10m",
      exec: "drag",
    },
    nearby_spike: {
      executor: "ramping-arrival-rate",
      startRate: 50,
      timeUnit: "1s",
      preAllocatedVUs: 80,
      maxVUs: 320,
      stages: [
        { target: 50, duration: "2m" },
        { target: 500, duration: "5s" },
        { target: 50, duration: "3m" },
      ],
      exec: "spike",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    nearby_req_duration_ms: ["p(50)<80", "p(95)<150", "p(99)<500"],
    nearby_429_rate: ["rate<0.40"],
    nearby_5xx_rate: ["rate<0.01"],
    nearby_error_rate: ["rate<0.02"],
  },
};

function randomChoice(items) {
  return items[Math.floor(Math.random() * items.length)];
}

function buildBbox(lat, lng, spread = 0.01) {
  const latOffset = (Math.random() - 0.5) * spread;
  const lngOffset = (Math.random() - 0.5) * spread;
  const swLat = lat + latOffset - spread * 0.5;
  const swLng = lng + lngOffset - spread * 0.5;
  const neLat = lat + latOffset + spread * 0.5;
  const neLng = lng + lngOffset + spread * 0.5;
  return `${swLat.toFixed(6)},${swLng.toFixed(6)},${neLat.toFixed(6)},${neLng.toFixed(6)}`;
}

function requestNearby(bbox, category, sort = "distance", scenario = "steady") {
  const query = `/v1/poi/nearby?bbox=${bbox}&category=${category}&sort=${sort}&limit=${LIMIT}`;
  const response = http.get(`${BASE}${query}`, {
    tags: {
      endpoint: "poi_nearby",
      scenario,
    },
  });

  nearbyLatency.add(response.timings.duration);
  nearby429Rate.add(response.status === 429);
  nearby5xxRate.add(response.status >= 500);
  nearbyErrorRate.add(response.status >= 400 && response.status !== 429);

  check(response, {
    "nearby status is 200 or 429": (res) => res.status === 200 || res.status === 429,
  });

  return response;
}

export function steady() {
  const pivot = randomChoice(CITY_CENTERS);
  const bbox = buildBbox(pivot.lat, pivot.lng, 0.015);
  requestNearby(bbox, pivot.category, "distance", "steady");
  sleep(0.05);
}

export function drag() {
  const pivot = randomChoice(CITY_CENTERS);
  for (let i = 0; i < 20; i += 1) {
    const bbox = buildBbox(pivot.lat + i * 0.0002, pivot.lng - i * 0.00015, 0.010);
    requestNearby(bbox, pivot.category, i % 3 === 0 ? "popularity" : "distance", "drag");
    sleep(0.5);
  }
}

export function spike() {
  const pivot = randomChoice(CITY_CENTERS);
  const bbox = buildBbox(pivot.lat, pivot.lng, 0.012);
  requestNearby(bbox, pivot.category, "rating", "spike");
}
