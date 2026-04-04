---
title: "StayVista 기술 개발기 53: [핵심] LLM Model Registry + Fallback - 모델 전환을 코드 변경 없이 제어하기"
slug: "53-llm-model-registry-fallback"
series: "StayVista 기술 개발기"
order: 53
prev_slug: "52-llm-budget-controller"
next_slug: "54-structured-output-repair"
status: "publish-ready"
excerpt: "LLM 장애는 피할 수 없고, 중요한 건 장애 순간에 어떤 모델로 안전하게 전환하느냐입니다. StayVista는 `LlmModelRegistry`로 active/fallback/embed를 분리해 설정으로 즉시 제어합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 53: [핵심] LLM Model Registry + Fallback - 모델 전환을 코드 변경 없이 제어하기

## 한 줄 요약
LLM 장애는 피할 수 없고, 중요한 건 장애 순간에 어떤 모델로 안전하게 전환하느냐입니다. StayVista는 `LlmModelRegistry`로 active/fallback/embed를 분리해 설정으로 즉시 제어합니다.

## 왜 모델 레지스트리를 분리했나
초기에는 모델명이 서비스 코드 곳곳에 박혀 있었습니다. 이 구조는 다음 문제가 있었습니다.

- 모델 변경 때 코드 수정/재실행이 필요함
- 생성 모델과 임베딩 모델이 섞여 설정 실수 발생
- 장애 시 fallback 동작 기준이 모호함

그래서 모델 선택을 `LlmModelRegistry` 단일 컴포넌트로 통합했습니다.

## 현재 구현 구조
`LlmModelRegistry`는 설정값을 다음처럼 분리합니다.

- `stayvista.chat.llm.active-model`: 기본 생성 모델
- `stayvista.chat.llm.fallback-model`: 비상 전환 모델(옵션)
- `stayvista.chat.embed.active-model`: 임베딩 모델
- `stayvista.chat.debug.expose-model-version`: 응답 debug 노출 여부

핵심은 "생성 경로"와 "임베딩 경로"를 분리한 점입니다.

- 생성: `ChatService`, `ChatShadowService`, `SearchCopilotService`가 참조
- 임베딩: `LocalRagRetriever`, `SemanticCacheService`, `RagIndexBuilderService`가 참조

즉 모델 교체 영향 반경을 코드가 아니라 레지스트리 경계에서 제어합니다.

## Fallback이 실제로 작동하는 지점
`ChatShadowService`는 shadow 모델 결정 시:

1. `stayvista.chat.shadow.model`이 있으면 우선 사용
2. 없으면 `modelRegistry.fallbackModel()` 사용
3. fallback도 없으면 `activeModel()` 사용

이 순서 덕분에 shadow 트래픽은 실험/검증용으로 분리되면서도, 설정 누락 시 완전히 멈추지 않습니다.

## 기술적으로 중요한 포인트
### 1) 생성 모델과 임베딩 모델은 수명주기가 다르다
생성 모델은 품질/비용/지연 변화가 크고, 임베딩 모델은 인덱스 재구축 비용이 큽니다.
둘을 같은 변수로 묶으면 변경 리스크가 급격히 커집니다.

### 2) fallback은 "없는 게 정상"이어야 한다
`fallback-model`은 공백 허용이고 런타임에서 `null` 처리합니다.
이 설계는 fallback 강제 의존을 없애고, 설정 정책으로만 활성화하도록 만듭니다.

### 3) 디버그 노출은 제어 가능해야 합니다
`shouldExposeModelVersion()`로 응답의 `debug.model` 노출을 on/off 합니다.
보안/프라이버시 요구사항에 따라 모델 식별자 노출을 줄일 수 있습니다.

## 로컬 실험에서 보는 지표
- `chat_route_total{route}`
- `llm_used_rate{used}`
- `chat_llm_fail_total{reason}`
- `chat_shadow_total{result}`

모델 전환 자체보다 중요한 건 전환 전후의 `fallback rate`, `latency`, `quality`를 비교하는 것입니다.

## 실패 시나리오와 대응
### 시나리오 1) active 모델 장애
- 징후: `chat_llm_fail_total` 급증, queue reject 증가
- 대응: `fallback-model` 지정 후 shadow 포함 점진 전환

### 시나리오 2) 임베딩 모델 변경
- 징후: 검색 relevance 하락, semantic cache miss 증가
- 대응: `RagIndexBuilderService` 재색인과 함께 rollout

## 개선 과제
- 모델별 비용/토큰 사용량 지표 분리
- 모델 버전별 품질 회귀 자동 비교
- fallback 전환 체크리스트 자동화

모델 레지스트리는 작은 클래스지만, LLM 모델 변경 비용과 장애 반경을 가장 크게 줄이는 레버입니다.
