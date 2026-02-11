# I-0522 — Shadow Mode: 모델/프롬프트 비교(실사용 트래픽 그림자 평가)

## Goal
실사용 트래픽을 이용해 새 모델/프롬프트를 "응답은 하나만 반환"하면서 뒤에서 비교 평가한다.

## Scope
- shadow enabled 시:
  - primary route는 그대로 응답
  - shadow route는 비동기로 실행(응답에 영향 없음)
  - 결과/스코어 저장(PII 제거 필수)

## Data
- shadow_run(id, ts, route_primary, route_shadow, model_primary, model_shadow, metrics_json)
- shadow_sample(shadow_run_id, request_redacted, response_redacted)

## Safety
- 저장 전 `PiiRedactor` 적용 필수

## Acceptance Criteria
- 운영에서 canary 없이도 품질 비교 가능
- shadow가 지연/오류를 일으켜도 사용자 응답 영향 없음
