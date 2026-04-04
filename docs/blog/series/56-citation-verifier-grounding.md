---
title: "StayVista 기술 개발기 56: [핵심] Citation Verifier - \"근거 없는 확신\"을 차단하는 마지막 안전장치"
slug: "56-citation-verifier-grounding"
series: "StayVista 기술 개발기"
order: 56
prev_slug: "55-memory-preference-rerank"
next_slug: "57-chat-safety-guardrails"
status: "publish-ready"
excerpt: "LLM이 문장을 그럴듯하게 만들어도, 근거가 없으면 서비스 리스크입니다. StayVista는 `CitationVerifier`로 assertive claim을 감지하고 source 없는 응답을 차단합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 56: [핵심] Citation Verifier - "근거 없는 확신"을 차단하는 마지막 안전장치

## 한 줄 요약
LLM이 문장을 그럴듯하게 만들어도, 근거가 없으면 서비스 리스크입니다. StayVista는 `CitationVerifier`로 assertive claim을 감지하고 source 없는 응답을 차단합니다.

## 왜 이 레이어가 필요한가
구조적 JSON 파싱이 성공해도 다음 문제는 남습니다.

- "확정", "보장", "항상" 같은 강한 단정
- 가격/재고/환불 등 민감 도메인 단언
- 금액 표현(숫자+원/KRW) 포함된 확정 표현

특히 OTA 맥락에서 이 단정은 CS/신뢰도 하락으로 바로 이어집니다.

## 현재 검증 로직
`CitationVerifier.verifyOrMitigate(result)`는 크게 3단계입니다.

1. assertive claim 탐지 (`hasAssertiveClaim`)
2. 카드/응답 source 정상화(`card.source`/`card.sources` 병합)
3. assertive + source 없음이면 차단 응답으로 치환

차단 시:
- 추천 카드 제거
- `llm_used=false`
- `context_used.citation_guard=assertion_blocked`
- `citation_verifier_block_total{reason=assertion_without_source}` 증가

## 기술적으로 중요한 포인트
### 1) "소스 유무"는 후처리 단계에서 한 번 더 확인해야 합니다
프롬프트에 sources를 요구해도 실제 출력 누락은 자주 발생합니다.
그래서 파서 단계와 별도로 verifier 단계가 필요합니다.

### 2) assertive 패턴은 보수적으로 적용합니다
패턴은 현재 한국어 단정어/정책어/가격표현 중심입니다.
오탐 가능성을 감수하더라도, 고위험 단정 누락을 먼저 줄이는 전략입니다.

### 3) 검증 실패 시 "안전한 다음 행동"을 제안해야 합니다
완전 실패보다 사용자가 조건을 구체화하도록 유도하는 답변이 낫습니다.
현재 차단 문구는 바로 재질문 가능한 형태로 고정되어 있습니다.

## ChatService 내 적용 위치
`recommend()`/`recommendStream()` 양쪽에서 공통 적용:

1. LLM/Template 결과 생성
2. `safetyPolicy.enforceOutputPolicy(...)`
3. `citationVerifier.verifyOrMitigate(...)`
4. 최종 응답 반환

즉 streaming/non-streaming 모두 동일한 grounding guard를 통과합니다.

## 로컬 검증 지표
- `citation_verifier_block_total{reason}`
- `chat_route_total{route}`
- `chat_llm_fail_total{reason}`

block율은 단순히 낮은 게 목표가 아닙니다. 단정형 응답 비율과 함께 해석해야 합니다.

## 리스크와 개선
### 리스크 1) false positive
안전 정책이 너무 강하면 유효 응답도 차단할 수 있습니다.

### 리스크 2) source quality 미검증
현재는 source 존재 여부 중심이며 source의 실제 정확성 검증은 제한적입니다.

### 개선 과제
- assertion 패턴을 정책 파일로 분리해 실험 가능하게 만들기
- source 품질 점수(최신성/신뢰도) 기반 완화 규칙 도입
- 차단 응답 후 recovery prompt 자동 제안

LLM 안전성은 "위험 응답을 잘 만드는 것"이 아니라 "위험 응답을 내보내지 않는 것"에서 결정됩니다.
