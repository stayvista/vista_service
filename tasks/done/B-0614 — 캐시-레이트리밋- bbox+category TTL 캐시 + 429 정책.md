# B-0614 — 캐시/레이트리밋: bbox+category TTL 캐시 + 429 정책

## Goal
지도 드래그/줌에서 발생하는 반복 요청으로 DB가 터지지 않도록 보호한다.

## Cache
- Redis(권장) 또는 in-memory(DEV)
- key: `nearby:{bboxHash}:{category}:{sort}:{limit}`
- TTL: 10~30s (짧게)

## Rate Limit
- key: anon_id/user_id/ip
- token bucket: (예) 20 req / 10s, burst 10
- 초과 시 429 + `retry_after_ms`

## Metrics
- cache_hit_rate_nearby
- rate_limited_count

## Acceptance Criteria
- 동일 bbox 반복 호출에서 cache hit > 80%
- 폭주 시 DB QPS가 제한되며 429로 보호됨
