# I-0989 — AI Copilot 전환 퍼널-SLO 대시보드 v1

## Goal
AI 여행도우미 고도화 이후 실제 전환 기여도를 측정하고, 품질 저하를 조기에 감지할 수 있는 운영 대시보드를 구축한다.

## Scope
- 퍼널 지표 추가
  - widget_open → prompt_submit → action_apply → search_result_click → booking_hold → booking_confirm
- 품질 지표 추가
  - clarify_rate, recovery_rate, no_result_rate, fallback_rate
- SLO 알람
  - orchestrator p95/p99
  - widget error rate
  - action_apply success rate
- 런북 추가
  - 알람 발생 시 1차 점검 순서
  - degrade 강제 전환 기준

## Acceptance Criteria
- 퍼널/품질/SLO 지표가 단일 대시보드에서 확인된다
- 주간 리포트에서 AI 유입 대비 예약 전환율을 확인할 수 있다
- 오류 급증 시 알람이 발생하고 런북 링크로 즉시 대응할 수 있다
- 배포 전후 비교(7일 이동평균)로 회귀 여부를 판단할 수 있다
