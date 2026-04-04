---
title: "StayVista 기술 개발기 36: Search Copilot - Facet 기반 필터 추천과 LLM Degrade 설계"
slug: "36-search-copilot-filters"
series: "StayVista 기술 개발기"
order: 36
prev_slug: "34-price-calendar-fallback-fx"
next_slug: "39-autocomplete-degrade"
status: "publish-ready"
excerpt: "검색 코파일럿의 본질은 \"새 결과 생성\"이 아니라 \"현재 결과를 더 잘 좁히는 필터 제안\"입니다. StayVista는 heuristic을 기본으로 두고 LLM은 선택적으로 덧씌웁니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 36: Search Copilot - Facet 기반 필터 추천과 LLM Degrade 설계

## 한 줄 요약
검색 코파일럿의 본질은 "새 결과 생성"이 아니라 "현재 결과를 더 잘 좁히는 필터 제안"입니다. StayVista는 heuristic을 기본으로 두고 LLM은 선택적으로 덧씌웁니다.

## 문제 정의
사용자는 검색 결과가 많을 때 무엇을 먼저 좁혀야 할지 모릅니다.
하지만 코파일럿이 재고/가격을 단정하면 위험합니다.

목표는 다음 2개입니다.
- 즉시 적용 가능한 필터 추천
- 과도한 확신/환각 없이 degrade 가능한 동작

## SearchCopilotService 구조
`recommend(request)` 흐름:

1. heuristic 추천 생성 (`heuristicRecommendation`)
2. LLM 비활성화면 heuristic 그대로 반환
3. LLM 활성화면 prompt 구성 후 JSON 응답 파싱
4. 파싱 실패/예외 시 heuristic degrade 반환

즉 heuristic이 항상 안전한 baseline입니다.

## Heuristic 추천 로직
현재 필터(`current_filters`)에 없는 항목만 추천 대상으로 삼습니다.
facet summary에서 상위 후보를 가져와 이유를 붙입니다.

주요 추천 키:
- `stars`
- `property_type`
- `amenities`
- `districts`
- `guest_rating_bands`
- `distance_bands`
- `payment_options`

최대 4개로 제한해 적용 부담을 줄입니다.

## LLM 레이어의 역할
LLM은 heuristic payload를 입력으로 받아:
- 설명 문장 정교화
- 추천 조합 보완
을 수행합니다.

시스템 프롬프트에서는 다음을 강제합니다.
- 제공된 facet key 안에서만 추천
- inventory/price guarantee 금지
- 짧고 구체적인 설명

## 기술적으로 중요한 포인트
### 1) heuristic first가 안전한 기본값
LLM 실패 시에도 유의미한 결과를 반환할 수 있습니다.
AI 기능이 core search 안정성을 해치지 않습니다.

### 2) JSON 파싱 실패는 즉시 degrade
`parseLlmResponse`에서 스키마가 맞지 않으면 fallback을 사용합니다.
응답 계약 위반을 downstream으로 넘기지 않습니다.

### 3) 추천 대상은 "현재 미적용 필터"로 제한
이미 적용된 필터를 반복 추천하면 사용자 신뢰가 떨어집니다.
현재 상태를 입력으로 넣는 이유입니다.

## 로컬 검증 지표(권장)
현재 서비스 코드에는 전용 메트릭이 많지 않으므로, 로컬 검증에서는 아래 항목을 함께 관측하는 것이 좋습니다.

- llm_used 비율
- degraded 비율
- 추천 필터 적용 클릭률
- 적용 후 검색 전환율

## 리스크와 완화
### 리스크 1) facet 편향
상위 facet만 추천하면 다양성이 떨어질 수 있습니다.

### 리스크 2) 설명만 좋고 행동성이 낮음
추천 이유가 길어도 적용값이 모호하면 실사용성이 낮습니다.

### 리스크 3) LLM drift
모델 업데이트로 JSON 품질이 흔들릴 수 있습니다.

완화 전략은 baseline heuristic 유지 + LLM 결과 검증 + degrade 고정입니다.

## 개선 과제
- 추천 필터별 실적 피드백 루프 구축
- 사용자 세그먼트별 추천 정책 분화
- explainability 로그 표준화

검색 코파일럿은 "답을 대신 찾는 기능"이 아니라 "필터 선택 비용을 줄이는 기능"으로 설계해야 오래 갑니다.
