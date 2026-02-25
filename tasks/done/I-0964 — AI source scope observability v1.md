# I-0964 — AI source scope observability v1

## Goal
AI 위젯 추천 범위(source scope)가 퍼널 성과에 미치는 영향을 운영에서 확인할 수 있도록 대시보드/런북 관측 포인트를 확장한다.

## Metrics
- `ai_widget_source_scope_total{event,scope}`
- `ai_widget_handoff_scope_total{scope}`
- 기존 handoff 품질 메트릭과의 조합
  - `ai_widget_handoff_confidence`
  - `ai_widget_handoff_filter_count`

## Scope
- source scope별 위젯 이벤트 분포 패널 추가
- scope별 handoff 품질 비교 패널 추가 (confidence/filter_count)
- 런북에 scope 불균형/이상치 점검 절차 추가

## Acceptance Criteria
- 운영자가 일 단위로 scope별 이벤트·handoff 품질을 비교할 수 있다
- 특정 scope에서 confidence 급락/empty handoff 급증 시 탐지 근거를 확보할 수 있다
