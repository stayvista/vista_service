---
title: "StayVista 기술 개발기 70: [핵심] 로컬 메트릭 계측 설계 - 회귀를 빠르게 잡는 최소 계측"
slug: "70-observability-slo"
series: "StayVista 기술 개발기"
order: 70
prev_slug: "69-llm-health-ready-probe"
next_slug: "71-telemetry-event-contract"
status: "publish-ready"
excerpt: "로컬 개발에서는 지표 개수를 늘리기보다, 코드 변경 회귀를 바로 판정할 수 있는 계측 지점을 고정하는 것이 더 중요했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 70: [핵심] 로컬 메트릭 계측 설계 - 회귀를 빠르게 잡는 최소 계측

## 한 줄 요약
로컬 개발 단계에서는 "화려한 시각화"보다, 수정한 코드가 정합성/지연/오류에 어떤 영향을 줬는지 즉시 판단할 수 있는 최소 계측이 핵심이었습니다.

## 전제
이 프로젝트는 외부 공개 없이 로컬에서만 개발/테스트를 진행했습니다. 그래서 계측 목표도 다음 3개로 제한했습니다.

- 변경 전후 비교가 가능할 것
- 실패 원인을 코드 레이어까지 좁힐 수 있을 것
- 반복 실행(k6/통합테스트)에서 같은 결론이 나올 것

## 계측 레이어를 코드에 고정한 방식
### 1) 입구 필터 계층
`AuthGuardFilter`, `TrafficGuardFilter`, `RequestIdFilter`에서 요청 입구 지표를 기록했습니다.

- `auth_guard_reject_total`
- `rate_limited_total`
- `abuse_block_total`

컨트롤러/서비스까지 들어가기 전에 차단된 요청 비율을 따로 볼 수 있어서, 도메인 버그와 입구 정책 이슈를 분리하기 쉬웠습니다.

### 2) 트랜잭션 계층
예약/티켓 핵심 경합 지점에 충돌 지표를 붙였습니다.

- `booking_overbooked_total`
- `booking_confirm_inventory_conflict_total`
- `ticket_confirm_inventory_conflict_total`
- `db_retry_total`

핵심은 "실패가 났다"가 아니라 "어느 단계에서 실패했는가"를 태그로 분리하는 것이었습니다.

### 3) 검색/가격 계층
조회 API의 fallback/성능 변화를 바로 볼 수 있게 구성했습니다.

- `search_requests_total`
- `search_opensearch_errors_total`
- `search_opensearch_empty_fallback_total`
- `search_latency_ms`
- `price_calendar_requests_total`
- `price_calendar_latency_ms`

### 4) AI 계층
LLM 품질보다 먼저 실패 패턴을 분해할 수 있게 메트릭을 분리했습니다.

- `chat_requests_total`
- `chat_llm_fail_total`
- `llm_reject_rate`
- `chat_ttfb_ms`
- `chat_stream_duration_ms`

## 메트릭 명명/태그 규칙
로컬 비교 가능성을 위해 다음 규칙을 유지했습니다.

- suffix를 역할별로 고정했습니다.
  - 카운터: `_total`
  - 타이머/분포: `_ms`, `_seconds`
- 태그 cardinality를 제한했습니다.
  - 사용자 ID, 요청 본문 같은 고카디널리티 값은 태그로 넣지 않았습니다.
- 도메인 prefix를 통일했습니다.
  - `booking_*`, `search_*`, `chat_*`, `ai_widget_*`

## 로컬 회귀 판정 루프
실행 루프는 단순하게 유지했습니다.

1. `services/loadtest/k6/*.js`에서 대상 시나리오를 실행합니다.
2. 변경 전후 메트릭(지연/실패/충돌)을 비교합니다.
3. 이상 구간이 있으면 해당 서비스 메서드와 SQL 경로를 재확인합니다.
4. 수정 후 동일 시나리오를 다시 실행합니다.

## 기술적으로 중요했던 점
- 메트릭은 나중에 붙이지 않고, 기능 코드와 같이 넣어야 비교 기준이 흔들리지 않습니다.
- 지표 수를 늘리기보다 "판정 가능한 지표"만 남겨야 회귀 판단 속도가 빨라집니다.
- 로컬 프로젝트에서는 절대 수치보다 "이전 커밋 대비 변화량"이 더 신뢰할 만한 기준이었습니다.
