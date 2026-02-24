# U-0986 — AI 위젯 한글 IME 엔터 전송 안정화 UX v1

## Goal
한글 IME 조합 입력 중 Enter 전송 실패를 줄여 AI 위젯 입력 경험을 안정화한다.

## Scope
- 텍스트 입력 컴포저에서 `Enter` 전송/`Shift+Enter` 줄바꿈 규칙 유지
- IME 조합 중 Enter 입력 시 조합 종료 후 자동 전송 보강
- 전송 경로를 `button`, `keyboard_enter`, `keyboard_shortcut`, `history_submit`으로 구분
- 최근 요청 `실행` 액션은 즉시 실행 경로(`history_submit`)로 일관화

## Acceptance Criteria
- 한글 조합 입력 직후 Enter로도 추천 요청이 정상 전송된다
- Shift+Enter는 기존처럼 줄바꿈만 동작한다
- 버튼/키보드/히스토리 실행 경로가 구분되어 이벤트로 전송된다
