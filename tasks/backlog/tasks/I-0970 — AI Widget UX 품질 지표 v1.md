# I-0970 — AI Widget UX 품질 지표 v1

## Goal
AI 위젯 고도화(U-0972/B-0972) 이후 사용성 개선 효과를 정량 추적해 회귀를 빠르게 감지한다.

## Metrics
- `ai_widget_session_restore_total{result}`
- `ai_widget_reset_total`
- `ai_widget_time_to_first_response_ms`
- `ai_widget_enter_submit_total`
- `ai_widget_quick_prompt_ctr`
- `ai_widget_handoff_apply_rate`

## Scope
- 프론트 이벤트 추가: 세션 복구/리셋/엔터 전송/빠른프롬프트 클릭
- 대시보드 패널 추가
  - 복구 성공률
  - 첫 응답 시간 p50/p95
  - handoff 적용률
- 알람 초안
  - 복구 실패율 급증
  - 첫 응답 p95 임계 초과

## Acceptance Criteria
- 배포 후 일 단위로 세션 복구율과 첫 응답 시간을 확인할 수 있다
- 엔터 전송 비율과 빠른프롬프트 클릭률을 비교해 입력 UX 효과를 판단할 수 있다
- 알람 발생 시 Runbook 링크로 즉시 대응 가능하다
