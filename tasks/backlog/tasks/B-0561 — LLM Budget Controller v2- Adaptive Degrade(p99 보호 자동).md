# B-0561 — LLM Budget Controller v2: Adaptive Degrade(p99 보호 자동)

## Goal
p99/queue 상황에 따라 LLM 사용률을 자동으로 낮춰 서비스 전체를 보호한다.

## Acceptance Criteria
- 스파이크 상황에서도 hard timeout에 붙지 않는다
