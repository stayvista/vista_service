# B-0458 — 캐시: Retrieval Cache + Prompt Cache + Singleflight

## Goal
반복 질문에서 비용/지연을 줄이고, 캐시 스탬피드로 DB/LLM이 폭주하는 상황을 방지한다.

## Cache Layers
1) Retrieval cache (Redis)
- key: `(city,intent,query_norm,filters_hash)`
- value: topK docs (doc_id/snippet)
- TTL: 10m

2) Prompt-result cache (Redis)
- key: `(model,prompt_hash)`
- value: structured JSON response
- TTL: 1~3m

3) Singleflight
- 동일 key 요청이 동시에 들어오면 1회만 compute, 나머지는 await

## Metrics
- `cache_hit_rate_retrieval`
- `cache_hit_rate_prompt`
- `singleflight_dedup_rate`

## Acceptance Criteria
- 반복 질문에서 llm_used=false 비율이 증가(캐시 hit)
- stampede 상황에서 DB/LLM QPS가 폭증하지 않음
