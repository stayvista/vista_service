---
title: "StayVista 기술 개발기 54: [핵심] Structured Output + Repair - LLM JSON 실패를 검증 가능한 오류로 바꾸는 방법"
slug: "54-structured-output-repair"
series: "StayVista 기술 개발기"
order: 54
prev_slug: "53-llm-model-registry-fallback"
next_slug: "55-memory-preference-rerank"
status: "publish-ready"
excerpt: "LLM 응답 파싱 실패는 예외가 아니라 상수입니다. StayVista는 strict parser + repair pass + template fallback 3단계로 실패를 흡수합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 54: [핵심] Structured Output + Repair - LLM JSON 실패를 검증 가능한 오류로 바꾸는 방법

## 한 줄 요약
LLM 응답 파싱 실패는 예외가 아니라 상수입니다. StayVista는 strict parser + repair pass + template fallback 3단계로 실패를 흡수합니다.

## 문제 정의
LLM이 "대체로 JSON처럼 보이는 텍스트"를 내더라도, 서비스 API에는 유효 JSON 계약이 필요합니다.

- 필수 필드 누락
- 배열/타입 불일치
- source 없는 카드
- markdown 섞임

이런 출력은 프론트/후처리 파이프라인을 즉시 불안정하게 만듭니다.

## 구현 축
### 1) 첫 번째 방어선: Strict Parser
`StructuredChatParser.parseStrict()`는 다음을 강제합니다.

- root object
- `assistant_text` 필수 문자열
- `cards` 배열
- 각 card의 `sources`(또는 legacy `source`) 배열 필수
- `followups` 배열
- `llm_used` boolean

조건 불만족 시 `StructuredOutputParseException`을 던집니다.

### 2) 두 번째 방어선: Repair Pass
`ChatService.parseStructuredOutputWithRepair()`는 1차 파싱 실패 시:

1. `chat_json_parse_fail_total{phase=primary}` 증가
2. `promptFactory.buildRepairPrompt(raw)`로 재정규화 프롬프트 생성
3. `llmClient.generate(..., maxTokens=420)`로 repair 호출
4. 재파싱 성공 시 `structured_repair_success_rate{result=success}`

즉 파서 실패를 곧바로 사용자 실패로 전파하지 않습니다.

### 3) 세 번째 방어선: 최종 Fallback
repair도 실패하면 `StructuredRepairFailedException`으로 처리하고 템플릿 경로로 degrade합니다.

- `fallback_due_to_parse_rate` 기록
- retrieval 기반 템플릿 응답
- `context_used.route=exception_fallback`

## 기술적으로 중요한 포인트
### 1) "느슨한 파싱"을 허용하지 않는다
초기에는 파싱 관용성을 높이면 편해 보이지만, 장기적으로 계약이 붕괴합니다.
strict parser는 품질 하한선을 지키는 안전장치입니다.

### 2) repair 모델 호출도 budget 대상으로 봐야 한다
repair는 숨은 LLM 호출이므로 비용/지연에 포함됩니다.
따라서 parse 실패율을 별도 모니터링해야 합니다.

### 3) source 없는 assertive 답변은 2차 검증에서 차단
파싱 성공만으로 끝나지 않고 `CitationVerifier`가 근거 기반 여부를 재검증합니다.
구조적 유효성과 사실적 안전성은 별개입니다.

## 로컬 검증 지표
- `chat_json_parse_fail_total{phase=primary|repair}`
- `structured_repair_success_rate{result}`
- `fallback_due_to_parse_rate{route}`
- `chat_llm_fail_total{reason=fallback}`

핵심은 parse fail 자체보다 "repair 후 생존률"과 "fallback 전환율"입니다.

## 로컬 실험에서 자주 보는 실패 패턴
- model 업데이트 직후 schema drift
- long context에서 sources 배열 누락
- stream 결과와 non-stream 결과 형식 차이

이 패턴은 parser 완화가 아니라 prompt/template/검증 회귀 테스트로 해결해야 합니다.

## 개선 과제
- 응답 JSON schema를 정적 파일로 분리하고 계약 테스트 자동화
- model/prompt version별 parse fail 지표 분리
- repair 실패 샘플의 원인 태깅 자동화

LLM을 신뢰할 수 있는 API로 만들려면 "생성 품질"보다 "실패 후 복구 경로"를 먼저 설계해야 합니다.
