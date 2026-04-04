---
title: "StayVista 기술 개발기 68: [핵심] Widget Session Snapshot - 상태 저장을 안전하게 만드는 스키마/크기/PII 방어"
slug: "68-widget-session-snapshot-hardening"
series: "StayVista 기술 개발기"
order: 68
prev_slug: "67-llm-client-timeout-streaming"
next_slug: "69-llm-health-ready-probe"
status: "publish-ready"
excerpt: "위젯 세션 저장은 편의 기능처럼 보이지만, 잘못 구현하면 개인정보 유출과 데이터 호환성 문제를 동시에 일으킵니다. StayVista는 schema version, payload budget, redaction으로 이를 제어합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 68: [핵심] Widget Session Snapshot - 상태 저장을 안전하게 만드는 스키마/크기/PII 방어

## 한 줄 요약
위젯 세션 저장은 편의 기능처럼 보이지만, 잘못 구현하면 개인정보 유출과 데이터 호환성 문제를 동시에 일으킵니다. StayVista는 schema version, payload budget, redaction으로 이를 제어합니다.

## 무엇을 저장하나요
`/v1/chat/widget/session/snapshot` API는 사용자별 위젯 상태를 저장/조회합니다.

- 저장: `POST /v1/chat/widget/session/snapshot`
- 조회: `GET /v1/chat/widget/session/snapshot`
- 식별자: `X-User-Id` 헤더

저장 키는 `chat:widget:snapshot:user:{userId}` 형식으로 고정합니다.

## ChatWidgetSessionService의 방어 계층
### 1) 스키마 버전 검증
- `schema_version > 0` 필수
- 서버 설정 `stayvista.chat.widget.snapshot.schema-version`과 일치해야 저장 허용

버전이 맞지 않으면 검증 오류로 즉시 차단합니다.

### 2) 상태 sanitize + PII 제거
- 문자열은 `PiiRedactor`로 마스킹 후 최대 600자 제한
- Map/List를 재귀 순회하며 동일 정책 적용
- 숫자/불리언만 원형 유지

즉 저장 이전에 민감정보와 과도한 페이로드를 먼저 줄입니다.

### 3) payload 크기 제한
- 직렬화 바이트 크기를 계산해 `max-bytes`(기본 65536) 초과 시 거절

이 제한이 없으면 Redis 메모리와 네트워크 대역폭이 빠르게 악화됩니다.

### 4) 저장 실패 graceful degrade
- Redis write 실패 시 예외를 던지지 않고 `accepted=false` 응답을 반환

사용자 요청을 5xx로 무조건 실패 처리하지 않아 UI 회복 흐름을 단순하게 유지할 수 있습니다.

## 조회(load) 경로의 안정성
조회 시에는 다음을 단계적으로 점검합니다.

1. Redis read 실패 -> `has_snapshot=false`
2. 데이터 없음 -> `has_snapshot=false`
3. 파싱 실패 -> `has_snapshot=false`
4. schema mismatch -> `has_snapshot=false`

즉 "불완전한 상태를 억지로 복원"하지 않고 안전하게 빈 상태로 복귀합니다.

## 기술적으로 중요한 포인트
### 1) 저장 계약과 조회 계약을 분리해야 합니다
저장은 엄격하게, 조회는 관용적으로 처리해야 개발 중 스키마 전환이 부드럽습니다.

### 2) 위젯 상태도 개인정보 저장 경로로 취급해야 합니다
대화 맥락에는 전화번호/이메일/카드 패턴이 섞일 수 있습니다.
PII redaction을 선택이 아닌 기본값으로 두셔야 합니다.

### 3) payload budget은 품질이 아니라 안정성 제약입니다
과도한 상태 저장은 캐시 장애의 시작점이 됩니다.
초기부터 바이트 상한을 강제하셔야 합니다.

## 로컬 검증 지표
- `ai_widget_snapshot_save_total{result}`
- `ai_widget_snapshot_load_total{result}`
- `ai_widget_snapshot_payload_bytes`

특히 `validation_error`, `parse_error`, `invalid_schema` 비율은 호환성 회귀를 빨리 알려줍니다.

## 개선 과제
- schema migration helper 제공
- state 필드 allowlist 기반 저장 정책
- 사용자 세그먼트별 TTL 차등화

세션 스냅샷은 "편의 캐시"가 아니라, 개인정보와 호환성을 함께 다루는 준영속 계층으로 설계하셔야 합니다.
