# I-0983 — AI Widget Error Recovery 관측성 v1

## Goal
오류 발생 이후 사용자의 복구 행동과 재전환 성과를 관측해 실패 UX를 개선한다.

## Scope
- 이벤트: `ai_widget_error_recovery_click`
- 핵심 지표
  - `ai_widget_error_recovery_action_total{action}`
  - `ai_widget_error_recovery_scope_total{action,scope}`
- 분석 관점
  - action별 사용 비율(`retry`, `restore_draft`, `reset_scope`, `dismiss`)
  - 복구 액션 이후 재질문/검색 전환율
  - 범위(scope)별 오류 복구 성공률

## Acceptance Criteria
- 오류 복구 액션별 지표가 분리 집계된다
- scope+action 조합으로 재시도 효율 분석이 가능하다
- 대시보드에서 실패-복구-전환 퍼널을 확인할 수 있다
