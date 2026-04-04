---
title: "StayVista 기술 개발기 92: [심화] API Envelope/에러 계약 - request_id 일관성과 예외 매핑"
slug: "92-api-envelope-error-contract"
series: "StayVista 기술 개발기"
order: 92
prev_slug: "91-traffic-guard-rate-limit-cost"
next_slug: "93-search-facet-taxonomy-fallback"
status: "publish-ready"
excerpt: "디버깅 속도는 에러 계약 일관성에 크게 좌우됩니다. StayVista는 모든 응답에 `request_id`를 포함하고, `GlobalExceptionHandler`에서 예외를 표준 코드로 매핑해 분석 경로를 단순화했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 92: [심화] API Envelope/에러 계약 - request_id 일관성과 예외 매핑

## 한 줄 요약
성공/실패 응답 포맷이 흔들리면 로그 추적과 클라이언트 처리 모두 복잡해집니다. API envelope를 고정하면 디버깅 경로가 짧아집니다.

## request_id 주입
`RequestIdFilter`가 요청 시작점에서 `request_id`를 생성/주입합니다.

- 입력 헤더: `X-Request-Id` (있으면 재사용)
- 없으면 UUID 생성
- response 헤더에도 동일 값 추가
- MDC(`request_id`)에 넣어 로그와 연결

## 공통 응답 포맷
`ApiResponses`가 성공/실패 포맷을 통일합니다.

- 성공: `ApiEnvelope{request_id, data}`
- 실패: `ApiErrorEnvelope{request_id, error{code,message,details}}`

도메인/컨트롤러별로 따로 포맷을 만들지 않도록 강제한 구조입니다.

## 예외 매핑 (`GlobalExceptionHandler`)
핵심 예외를 표준 코드로 변환합니다.

- `DomainException` -> 도메인 지정 `ErrorCode`
- `MethodArgumentNotValidException` -> `VALIDATION_ERROR` + field details
- `ConstraintViolationException` -> `VALIDATION_ERROR` + violation map
- `DuplicateKeyException`, `DataIntegrityViolationException` -> `CONFLICT`
- 기타 예외 -> `INTERNAL`

## ErrorCode 계약
`ErrorCode` enum에 HTTP status와 코드 문자열을 같이 정의했습니다.

예시:
- `BOOKING_OVERBOOKED` -> 409
- `QUEUE_REQUIRED` -> 429
- `PAYMENT_AUTH_FAILED` -> 409
- `VALIDATION_ERROR` -> 400

상태코드와 비즈니스 코드가 분리되어 있어 클라이언트가 안정적으로 분기할 수 있습니다.

## 기술적으로 중요한 포인트
- 모든 응답에 `request_id`가 있어야, 재현 없이도 로그를 역추적할 수 있습니다.
- 예외를 컨트롤러마다 처리하지 않고 중앙 매핑해야 코드가 단순해집니다.
- `details` 구조를 유지하면 오류 원인 분석 자동화가 쉬워집니다.

## 남은 과제
- 예외 유형별 표준 details 스키마 문서화
- 공통 에러 응답 snapshot 테스트 추가
- 에러 코드 변경 감지용 계약 테스트 도입
