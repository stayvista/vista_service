import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

export const options = {
  scenarios: {
    llm_off_steady: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.LLM_OFF_RATE || "25"),
      timeUnit: "1s",
      duration: __ENV.LLM_OFF_DURATION || "2m",
      preAllocatedVUs: 30,
      maxVUs: 120,
      exec: "runLlmOffSteady",
    },
    stream_steady: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.STREAM_STEADY_RATE || "8"),
      timeUnit: "1s",
      duration: __ENV.STREAM_STEADY_DURATION || "2m",
      preAllocatedVUs: 12,
      maxVUs: 80,
      exec: "runStreamSteady",
      startTime: "10s",
    },
    stream_spike: {
      executor: "ramping-arrival-rate",
      startRate: Number(__ENV.STREAM_SPIKE_START_RATE || "4"),
      timeUnit: "1s",
      preAllocatedVUs: 20,
      maxVUs: 200,
      stages: [
        { duration: __ENV.STREAM_SPIKE_WARMUP || "30s", target: Number(__ENV.STREAM_SPIKE_WARMUP_RATE || "8") },
        { duration: __ENV.STREAM_SPIKE_DURATION || "20s", target: Number(__ENV.STREAM_SPIKE_PEAK_RATE || "80") },
        { duration: __ENV.STREAM_SPIKE_COOLDOWN || "30s", target: Number(__ENV.STREAM_SPIKE_END_RATE || "8") },
      ],
      exec: "runStreamSpike",
      startTime: "20s",
    },
    stream_cache_hit: {
      executor: "constant-vus",
      vus: Number(__ENV.STREAM_CACHE_VUS || "10"),
      duration: __ENV.STREAM_CACHE_DURATION || "2m",
      exec: "runStreamCacheHit",
      startTime: "30s",
    },
  },
  thresholds: {
    chat_llm_off_p95: ["p(95)<250"],
    chat_stream_ttfb_ms: ["p(95)<500"],
    chat_stream_complete_ms: ["p(95)<2000"],
    chat_stream_failed: ["rate<0.02"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:18765";

const chatStreamFailed = new Rate("chat_stream_failed");
const chatLlmUsedRate = new Rate("chat_llm_used_rate");
const chatLlmOffP95 = new Trend("chat_llm_off_p95", true);
const chatStreamTtfbMs = new Trend("chat_stream_ttfb_ms", true);
const chatStreamCompleteMs = new Trend("chat_stream_complete_ms", true);

function postRecommend(message, context = {}) {
  const response = http.post(
    `${BASE}/v1/chat/recommend`,
    JSON.stringify({ message, context }),
    {
      headers: {
        "Content-Type": "application/json",
      },
    }
  );

  const ok = check(response, {
    "recommend status is 200": (r) => r.status === 200,
  });

  let payload = null;
  try {
    payload = JSON.parse(response.body);
  } catch {
    payload = null;
  }

  const llmUsed = Boolean(payload?.data?.llm_used);
  chatLlmUsedRate.add(llmUsed);
  chatStreamFailed.add(!ok);

  return { response, payload, llmUsed };
}

function postRecommendStream(message, context = {}) {
  const response = http.post(
    `${BASE}/v1/chat/recommend:stream`,
    JSON.stringify({ message, context }),
    {
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
      },
    }
  );

  const hasDone = response.body && response.body.includes("event: done");
  const ok = check(response, {
    "stream status is 200": (r) => r.status === 200,
    "stream includes done event": () => hasDone,
  });

  chatStreamTtfbMs.add(response.timings.waiting);
  chatStreamCompleteMs.add(response.timings.duration);
  chatStreamFailed.add(!ok);

  const donePayload = extractDonePayload(response.body || "");
  if (donePayload && typeof donePayload.llm_used === "boolean") {
    chatLlmUsedRate.add(donePayload.llm_used);
  }

  return { response, donePayload };
}

function extractDonePayload(body) {
  const marker = "event: done";
  const markerIndex = body.lastIndexOf(marker);
  if (markerIndex < 0) return null;

  const chunk = body.slice(markerIndex);
  const dataLine = chunk
    .split("\n")
    .map((line) => line.trim())
    .find((line) => line.startsWith("data:"));

  if (!dataLine) return null;

  const raw = dataLine.slice("data:".length).trim();
  if (!raw) return null;

  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function runLlmOffSteady() {
  const { response } = postRecommend("서울 숙소 추천", {
    city: "Seoul",
    days: 2,
    companions: "COUPLE",
  });
  chatLlmOffP95.add(response.timings.duration);
  sleep(0.05);
}

export function runStreamSteady() {
  postRecommendStream(
    "서울 2박3일 일정 추천해줘. 숙소, 티켓, 이동 동선을 간단히 정리해줘.",
    {
      city: "Seoul",
      days: 3,
      budget_krw: 700000,
      companions: "COUPLE",
    }
  );
  sleep(0.05);
}

export function runStreamSpike() {
  postRecommendStream(
    "3박4일 서울 여행인데 전시와 맛집 위주로 동선을 짜줘. 숙소와 티켓을 이유와 함께 추천해줘.",
    {
      city: "Seoul",
      days: 4,
      budget_krw: 900000,
      companions: "FRIENDS",
    }
  );
  sleep(0.02);
}

export function runStreamCacheHit() {
  postRecommendStream(
    "서울 2박3일 커플 여행 일정 추천해줘. 숙소랑 티켓도 같이 추천해줘.",
    {
      city: "Seoul",
      days: 3,
      budget_krw: 700000,
      companions: "COUPLE",
    }
  );
  sleep(0.05);
}
