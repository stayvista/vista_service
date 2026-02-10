import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

export const options = {
  scenarios: {
    chat_rules_or_template: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RULES_RATE || "20"),
      timeUnit: "1s",
      duration: __ENV.RULES_DURATION || "2m",
      preAllocatedVUs: 20,
      maxVUs: 80,
      exec: "runRulesScenario",
    },
    chat_llm_on: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.LLM_RATE || "8"),
      timeUnit: "1s",
      duration: __ENV.LLM_DURATION || "2m",
      preAllocatedVUs: 10,
      maxVUs: 40,
      exec: "runLlmScenario",
      startTime: "10s",
    },
    chat_cache_hit: {
      executor: "constant-vus",
      vus: Number(__ENV.CACHE_VUS || "5"),
      duration: __ENV.CACHE_DURATION || "2m",
      exec: "runCacheScenario",
      startTime: "20s",
    },
  },
  thresholds: {
    chat_rules_p95: ["p(95)<300"],
    chat_llm_p95: ["p(95)<1200"],
    chat_req_failed: ["rate<0.02"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:18765";

const reqFailed = new Rate("chat_req_failed");
const llmUsedRate = new Rate("chat_llm_used_rate");
const rulesP95 = new Trend("chat_rules_p95", true);
const llmP95 = new Trend("chat_llm_p95", true);
const cacheP95 = new Trend("chat_cache_p95", true);

function postChat(message, context = {}) {
  const res = http.post(
    `${BASE}/v1/chat/recommend`,
    JSON.stringify({ message, context }),
    {
      headers: {
        "Content-Type": "application/json",
      },
    }
  );
  reqFailed.add(res.status < 200 || res.status >= 400);
  check(res, { "chat status 200": (r) => r.status === 200 });

  let payload = null;
  try {
    payload = JSON.parse(res.body);
  } catch {
    payload = null;
  }

  const llmUsed = Boolean(payload?.data?.llm_used);
  llmUsedRate.add(llmUsed);
  return { res, llmUsed, payload };
}

export function runRulesScenario() {
  const { res } = postChat("서울 숙소 추천", { city: "Seoul", days: 2 });
  rulesP95.add(res.timings.duration);
  sleep(0.05);
}

export function runLlmScenario() {
  const { res } = postChat(
    "3박4일 서울 여행인데 전시와 맛집 위주로 동선을 짜줘. 숙소, 티켓, 주변 추천을 이유와 함께 비교해줘.",
    { city: "Seoul", days: 4, budget_krw: 900000, companions: "COUPLE" }
  );
  llmP95.add(res.timings.duration);
  sleep(0.1);
}

export function runCacheScenario() {
  const { res } = postChat(
    "서울 2박3일 커플 여행 일정 추천해줘. 숙소랑 티켓도 같이 추천해줘.",
    { city: "Seoul", days: 3, budget_krw: 700000, companions: "COUPLE" }
  );
  cacheP95.add(res.timings.duration);
  sleep(0.05);
}
