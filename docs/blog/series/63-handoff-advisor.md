---
title: "StayVista 기술 개발기 63: [핵심] Handoff Advisor - 대화 의도를 검색 필터로 변환하는 레이어"
slug: "63-handoff-advisor"
series: "StayVista 기술 개발기"
order: 63
prev_slug: "62-copilot-orchestrator"
next_slug: "64-rag-index-builder-incremental"
status: "publish-ready"
excerpt: "LLM 답변이 좋아도 검색에 반영되지 않으면 전환이 안 납니다. Handoff Advisor는 대화 신호를 실제 검색 조건으로 변환하는 연결 계층입니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 63: [핵심] Handoff Advisor - 대화 의도를 검색 필터로 변환하는 레이어

## 한 줄 요약
LLM 답변이 좋아도 검색에 반영되지 않으면 전환이 안 납니다. Handoff Advisor는 대화 신호를 실제 검색 조건으로 변환하는 연결 계층입니다.

## 역할
`ChatSearchHandoffAdvisor`는 입력을 받아:
- 추천 필터 목록
- 신뢰도(confidence)
- 보완질문(clarify)
- 누락 슬롯(missing slots)
- source type 추천
- search patch
를 만듭니다.

즉 "대화 결과를 검색 실행으로 넘기는 계약"을 만듭니다.

## 입력 신호
- message 텍스트
- slots(city/days/budget/companions/intent/sourceTypes)
- retrieval hits
- preference profile

## 출력의 핵심 구성
### 1) recommended_filters
의도/선호를 검색 파라미터로 매핑:
- intent -> themes/amenities
- companions -> family/group/romance 계열
- budget -> max_price 자동 계산
- 명소 히트 -> nearby_attractions
- sort hint -> 정렬 추천

### 2) clarify_required + missing_slots
정보가 부족하면 질문을 먼저 유도합니다.

### 3) recommended_source_types
PROPERTY/TICKET/PACKAGE/POI 범위를 제안해 결과 스코프를 맞춥니다.

## 관측성 설계
Advisor는 결과 품질을 촘촘히 계측합니다.

- `chat_search_handoff_total`
- `chat_search_handoff_filter_count`
- `chat_search_handoff_confidence`
- `chat_search_handoff_clarify_suggested_total`
- `chat_search_handoff_missing_slot_count`
- `chat_search_handoff_clarify_action_total{slot=*}`
- `chat_search_handoff_sort_hint_total`

이 지표가 바로 widget telemetry/alert 룰과 연결됩니다.

## 기술적으로 중요한 포인트
### 1) 규칙 기반 + 프로필 기반 하이브리드
현재 대화 신호와 과거 선호를 함께 사용하되, source를 구분해 추적 가능하게 만듭니다.

### 2) 필터 수 상한
추천 필터를 과도하게 늘리지 않고 상한(`take(6)`)으로 제어해 적용 복잡도를 낮춥니다.

### 3) confidence/clarify 분기
신뢰도 낮음 + 누락 슬롯 존재 시 보완질문을 우선합니다.

### 4) advisor 결과를 계약화
summary, filters, patch, actions를 구조화해 프론트가 일관되게 렌더링할 수 있습니다.

## 로컬 실험에서 자주 보는 이상 패턴
- clarify 비율 급증
- clarify CTR 급락
- source scope drift 증가
- 특정 slot 편중

이 패턴은 보통 슬롯 추출 룰 회귀, 프론트 payload 누락, prompt 변경 부작용 중 하나입니다.

## 개선 과제
- rule weight 자동 튜닝
- scope/intent confusion 학습형 보정
- 슬롯 추출 실패 샘플 자동 수집

Handoff Advisor는 추천 품질보다 전환율에 더 직접적인 영향을 줍니다. 이유는 간단합니다. 실제 검색 조건을 결정하기 때문입니다.

