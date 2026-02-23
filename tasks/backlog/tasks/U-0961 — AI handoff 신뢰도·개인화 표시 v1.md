# U-0961 — AI handoff 신뢰도·개인화 표시 v1

## Goal
홈 우측 AI 위젯에서 추천 필터의 품질/개인화 여부를 사용자에게 투명하게 보여주고, 실제 개인화 세션 컨텍스트를 서버로 전달한다.

## Scope
- handoff 패널에 신뢰도 배지(`신뢰도 XX%`) 표시
- handoff 패널에 `개인화 반영` 배지 표시
- handoff 근거(rationale) 목록 표시
- 채팅 요청 context에 `session_id`/`user_id` 전달
  - 익명 사용자도 `session_id`로 선호 누적 가능
- 기존 필터 선택/검색 핸드오프 동작 유지

## Acceptance Criteria
- handoff payload에 confidence가 있으면 UI에 퍼센트로 표시된다
- profile_applied=true면 개인화 배지가 표시된다
- rationale가 있으면 최대 3개까지 출력된다
- 로그인/비로그인 상태 모두 session_id가 일관되게 전달된다
- 기존 모바일/데스크톱 위젯 동작 회귀가 없다
