# I-0982 — AI Widget Prompt Submit Method 관측성 v1

## Goal
프롬프트 제출 경로별 사용 비율과 검색 전환 성과를 관측해 입력 UX 개선 우선순위를 정한다.

## Scope
- 이벤트: `ai_widget_prompt_submit`
- 핵심 지표
  - `ai_widget_prompt_submit_method_total{method}`
  - `ai_widget_prompt_submit_scope_total{method,scope}`
- 분석 관점
  - 버튼 대비 엔터 기반 전송 비중 추이
  - 히스토리 즉시실행(`history_submit`) 도입 후 전환 변화
  - method별 실패/재시도율(연계 이벤트 기준)

## Acceptance Criteria
- 제출 경로별 지표가 분리 집계된다
- scope+method 조합 기준에서 퍼널 분석이 가능하다
- 대시보드에서 IME 안정화 전/후 비교가 가능하다
