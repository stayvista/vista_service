# B-0611 — Nearby 검색 API v2: bbox 기반 + limit/offset + 정렬

## Goal
지도 UI에 맞게 “화면(viewport) 기준 검색”을 지원한다.

## API
- `GET /v1/poi/nearby`
  - query:
    - `bbox=swLat,swLng,neLat,neLng` (필수)
    - `category` (optional)
    - `limit` (default 50, max 200)
    - `sort=distance|popularity|rating` (default distance)
    - `center=lat,lng` (distance 정렬일 때 기준점; 없으면 bbox center)
- response:
  - `items[]`: `{id, name, category, lat, lng, distance_m, preview?}`
  - `meta`: `{bbox, returned, has_more}`

## Implementation Notes
- bbox로 1차 필터(인덱스 타게)
- distance는 2차 계산(하버사인) + 정렬
- limit을 엄격히 enforce(과부하 방지)

## Acceptance Criteria
- bbox 요청 p95 < 150ms(로컬/스테이징 기준)
- 잘못된 bbox 입력은 400 반환
