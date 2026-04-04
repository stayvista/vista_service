---
title: "StayVista 기술 개발기 71: [핵심] Telemetry Event Contract - 입력 검증과 메트릭 매핑을 서버에서 강제하기"
slug: "71-telemetry-event-contract"
series: "StayVista 기술 개발기"
order: 71
prev_slug: "70-observability-slo"
next_slug: "72-grafana-dashboard-correlation"
status: "publish-ready"
excerpt: "Telemetry 이벤트를 자유 입력으로 두면 테스트 데이터가 금방 오염됩니다. `TelemetryController`에서 이벤트별 필수 필드와 허용값을 강제해 분석 가능한 데이터만 남기도록 설계했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 71: [핵심] Telemetry Event Contract - 입력 검증과 메트릭 매핑을 서버에서 강제하기

## 한 줄 요약
Telemetry도 API 계약입니다. 서버 검증 없이 적재하면 이벤트 의미가 흔들리고, 실험 결과를 다시 신뢰하기 어려워집니다.

## 왜 계약이 필요했는가
로컬 개발에서도 이벤트 수집 경로는 쉽게 오염됐습니다.

- `event_name` 오타
- 이벤트별 필수 필드 누락
- enum 값 대소문자/표기 흔들림
- 동일 이벤트의 의미 불일치

이 상태를 막기 위해 `POST /v1/telemetry/events` 입력 단계에서 바로 실패시키는 방식을 택했습니다.

## 구현 핵심 (`TelemetryController`)
### 1) 허용 이벤트 화이트리스트
`ALLOWED_EVENTS` 밖의 이벤트는 즉시 `VALIDATION_ERROR`로 거절합니다.

### 2) 이벤트별 required 승격
같은 필드라도 이벤트에 따라 필수 여부를 다르게 강제합니다.

예시:
- `ai_widget_prompt_submit` -> `submit_method` 필수
- `ai_widget_card_save_click` -> `card_save_state`, `target_source_type`, `saved_card_count` 필수
- `ai_widget_first_response` -> `time_to_first_response_ms` 필수
- `ai_widget_search_blocked` -> `block_reason` 필수

### 3) 정규화 함수 분리
`normalize*` 계열 함수로 허용값을 고정합니다.

- `normalizeSourceTypeScope`
- `normalizeClarifySlot`
- `normalizeSubmitMethod`
- `normalizeRecoveryAction`

## 수집 후 즉시 메트릭으로 변환
수집 성공 여부만 기록하지 않고, 이벤트를 목적 지표로 바로 정규화했습니다.

- 공통: `ai_widget_event_total`, `ai_widget_source_scope_total`
- handoff 품질: `ai_widget_handoff_confidence`, `ai_widget_handoff_missing_slot_count`
- UX 액션: `ai_widget_prompt_submit_method_total`, `ai_widget_error_recovery_action_total`

이 구조 덕분에 raw event를 다시 파싱하지 않아도 비교 가능한 숫자를 바로 얻을 수 있었습니다.

## 테스트 근거
`TelemetryControllerTest`에서 다음을 검증합니다.

- 허용 이벤트 수집 성공
- 미허용 이벤트 거절
- 이벤트별 필수 필드 검증
- 태그 포함 카운터/분포 증가

## 기술적으로 중요한 포인트
- "저장 후 정제"가 아니라 "입력 단계 검증"을 선택해야 데이터 품질이 유지됩니다.
- 이벤트 스키마 변경 시 검증/메트릭 매핑을 동시에 수정해야 의미 드리프트를 막을 수 있습니다.
- Telemetry는 로깅이 아니라 도메인 계약이라는 관점이 유지보수 비용을 크게 줄였습니다.
