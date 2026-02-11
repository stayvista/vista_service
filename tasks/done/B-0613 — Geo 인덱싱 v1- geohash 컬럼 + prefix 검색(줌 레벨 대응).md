# B-0613 — Geo 인덱싱 v1: geohash 컬럼 + prefix 검색(줌 레벨 대응)

## Goal
MySQL에서 대규모 POI를 빠르게 검색하기 위한 geo 인덱싱을 도입한다(공간 인덱스 없이도 가능).

## Approach
- poi 테이블에 `geohash`(string) 컬럼 추가
- 인덱스: `(geohash, category)` 또는 `(category, geohash)`
- bbox 입력을 “대략적 geohash prefix 후보”로 변환
  - 줌 레벨/면적에 따라 prefix 길이를 동적으로 조절
- geohash prefix로 1차 후보를 줄인 뒤 bbox 정확 필터 + 거리 정렬

## Deliverables
- Flyway migration: geohash 컬럼 + 인덱스
- backfill job: 기존 데이터 geohash 채우기
- query util: bbox -> geohash prefixes

## Acceptance Criteria
- 100만 POI 가정 시에도 bbox 검색이 안정적으로 동작(부하 테스트로 확인)
