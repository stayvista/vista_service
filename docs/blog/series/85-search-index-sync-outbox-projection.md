---
title: "StayVista 기술 개발기 85: [확장] Search 인덱스 동기화 - Outbox 이벤트를 검색 문서로 투영하기"
slug: "85-search-index-sync-outbox-projection"
series: "StayVista 기술 개발기"
order: 85
prev_slug: "84-autocomplete-feedback-loop"
next_slug: "86-destination-recommendation-fallback"
status: "publish-ready"
excerpt: "카탈로그 변경을 검색 인덱스에 반영할 때 핵심은 재시도 가능성과 정합성입니다. StayVista는 outbox 이벤트를 `SearchIndexSyncService`에서 문서로 투영해 OpenSearch 동기화를 일관되게 처리했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 85: [확장] Search 인덱스 동기화 - Outbox 이벤트를 검색 문서로 투영하기

## 한 줄 요약
카탈로그 쓰기와 검색 인덱싱을 직접 엮지 않고, outbox 이벤트를 검색 문서로 변환하는 투영 계층을 두면 동기화 실패를 제어하기 쉬워집니다.

## 전체 흐름
1. `CatalogService`가 `PropertyUpserted`, `RoomTypeUpserted` 이벤트를 outbox에 기록합니다.
2. `OutboxRelayJob`가 `status=NEW` 이벤트를 읽습니다.
3. `SearchIndexSyncService.syncCatalogEvent`가 이벤트를 검색 문서로 변환합니다.
4. `OpenSearchClient.upsertProperty`로 alias(`properties`)에 upsert합니다.
5. 성공 시 outbox는 `PUBLISHED`, 실패 시 `FAILED`로 기록합니다.

## 이벤트 해석 규칙
`syncCatalogEvent`는 이벤트 타입별로 property ID를 해석합니다.

- `PropertyUpserted + PROPERTY`: `aggregateId`를 property ID로 사용
- `RoomTypeUpserted + ROOM_TYPE`: `room_type.property_id`를 조회해 대상 property를 계산

대상 property를 찾지 못하면 조용히 종료해 불필요한 예외 전파를 막습니다.

## 문서 투영 방식
property와 room_type을 합쳐 검색 문서 1건으로 만듭니다.

- property 필드: `name`, `city`, `country`, `status`, `rating`, `thumbnail_url`
- room type 배열: `room_type_id`, `name`, `max_guests`, `base_price`
- 파생값: `price_min = min(room_type.base_price)`
- 좌표가 있으면 `location(lat/lon)` 포함

## 인덱스/alias 보장
`ensureIndexAndAlias()`가 인덱스 존재와 alias 연결을 먼저 확인합니다.

- 인덱스가 없으면 매핑 생성
- alias(`properties`) 연결
- 이후 upsert는 alias 기준으로 수행

이 구조 덕분에 실제 조회 경로는 인덱스 이름을 직접 알 필요가 없습니다.

## 전체 재색인 경로
관리자 API `POST /v1/admin/search/reindex`로 `reindexAll(limit)`를 실행할 수 있습니다.

- `limit`가 있으면 앞에서 N개만 재색인
- 반환값: `scanned`, `upserted`, `failed`

## 실패 처리
- 인덱스 보장 실패: `search_index_ensure_fail_total`
- 문서 upsert 실패: `search_index_upsert_total{result=fail}`
- relay 실패: `outbox_failed_total`

실패를 이벤트 단위로 남기기 때문에 재처리 대상 추적이 단순합니다.

## 테스트 근거
`SearchIndexSyncServiceTest`에서 다음을 검증합니다.

- `reindexAll(limit=1)`이 실제로 한 건만 upsert하는지
- OpenSearch client가 예상 property ID로 호출되는지

## 기술적으로 중요한 포인트
- 검색 인덱스는 원본이 아니라 projection이므로, 투영 규칙이 명확해야 합니다.
- outbox 기반 동기화는 실패를 데이터로 남기기 때문에 재처리 전략을 세우기 쉽습니다.
- room type 변경 이벤트를 property 문서 재작성으로 연결해야 검색 결과 정합성이 유지됩니다.
