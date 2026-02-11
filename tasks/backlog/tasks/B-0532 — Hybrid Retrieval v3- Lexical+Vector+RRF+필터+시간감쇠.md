# B-0532 — Hybrid Retrieval v3: Lexical+Vector+RRF+필터+시간감쇠

## Goal
항상 hybrid(lex+vec) 후보를 만들고 RRF로 안정적인 recall을 확보한다.

## Acceptance Criteria
- golden set에서 0-result rate 감소
- retrieval p95 < 50ms (캐시 포함)
