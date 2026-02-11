# B-0541 — Memory v3: Running Summary + State Machine + 안전 저장

## Goal
대화가 길어져도 prompt 폭증 없이 상태(summary/state)로 대화를 유지한다.

## Acceptance Criteria
- 50턴 대화에서도 latency 악화 없음
- summary에 PII 저장 금지
