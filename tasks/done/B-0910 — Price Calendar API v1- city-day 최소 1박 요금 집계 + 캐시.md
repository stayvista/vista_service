# B-0910 — Price Calendar API v1

## Goal
캘린더에 날짜별 최소 1박 요금을 제공한다.

## API
- GET /v1/prices/calendar?place_id&from&to&currency&rooms/adults/children...

## Implementation (v1)
- CITY: city_day_min_price 사전 집계(권장)
- PROPERTY: rate_calendar 직접
- POI: city 변환 또는 radius(정책 고정)

## Caching
- Redis pc:{place}:{from}:{to}:{currency}:{guestsHash} TTL 10m

## Acceptance Criteria
- 2개월 조회 p95 < 150ms
