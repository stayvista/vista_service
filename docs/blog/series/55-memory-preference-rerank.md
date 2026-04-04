---
title: "StayVista 기술 개발기 55: [핵심] Memory + Preference Rerank - 대화 맥락과 취향을 응답 품질로 연결하는 레이어"
slug: "55-memory-preference-rerank"
series: "StayVista 기술 개발기"
order: 55
prev_slug: "54-structured-output-repair"
next_slug: "56-citation-verifier-grounding"
status: "publish-ready"
excerpt: "좋은 추천은 \"이번 질문\"만으로 만들기 어렵습니다. StayVista는 세션 메모리와 선호 프로필을 분리 저장하고, 최종 카드는 rerank로 재정렬합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 55: [핵심] Memory + Preference Rerank - 대화 맥락과 취향을 응답 품질로 연결하는 레이어

## 한 줄 요약
좋은 추천은 "이번 질문"만으로 만들기 어렵습니다. StayVista는 세션 메모리와 선호 프로필을 분리 저장하고, 최종 카드는 rerank로 재정렬합니다.

## 왜 분리 설계가 필요했나
대화형 추천에서 맥락은 두 종류입니다.

- 단기 상태: 지금 대화 흐름(일정 논의 중인지, 예약 직전인지)
- 장기 취향: 사용자가 반복적으로 선호/비선호하는 카테고리

둘을 하나의 상태 객체로 섞으면 TTL/업데이트 주기가 충돌합니다. 그래서 `ChatMemoryService`와 `PreferenceProfileService`를 분리했습니다.

## ChatMemoryService
### 세션 키 해석
`resolveSessionKey()`는 `user_id > session_id > conversation_id > anon` 순으로 키를 만듭니다.

### 저장 모델
- state: `COLLECTING` / `PLANNING` / `BOOKING_READY`
- runningSummary: 최근 대화 요약(길이 제한)
- turnCount

### 중요한 디테일
- PII redaction 후 요약 저장
- Redis 실패 시 `fallbackStore`(in-memory) 사용
- TTL 기본 7일

## PreferenceProfileService
### 피드백 입력 경로
- 암묵적 피드백: `recordImplicitFeedback(message)`
- 명시적 피드백: `applyExplicitFeedback(...)`

### 프로필 구조
- `tagWeights`: 자유 태그 선호/비선호 가중치
- `categoryWeights`: `PROPERTY/TICKET/PACKAGE/POI` 선호 가중치

### 개발 안정성 포인트
- Redis 장애 시 fallbackStore
- key 해석 우선순위 동일(`user/session/conversation`)
- TTL 기본 30일

## rerank가 실제로 붙는 지점
`ChatService.enrichResult()`에서:

1. profile load
2. `preferenceProfileService.rerank(...)`
3. reranked cards 기준으로 sources 재구성
4. 필요 시 itinerary 생성

즉 모델 출력은 "초안"이고, 사용자 컨텍스트 적용은 서비스 레이어에서 결정합니다.

## 기술적으로 중요한 포인트
### 1) 개인화는 생성 이전보다 생성 이후가 안전하다
LLM 프롬프트에 취향을 과도 주입하면 환각/과적합이 커질 수 있습니다.
현재 구조는 근거 카드 집합을 유지하면서 순서만 조정합니다.

### 2) 메모리 추론은 최소 규칙으로 유지
`deriveState()`는 키워드 기반 상태 전이만 수행합니다.
복잡한 상태 머신보다 오류 반경이 작고 디버깅이 쉽습니다.

### 3) proxy score 계측으로 개선 여부를 검증
`chat_reranker_proxy_score_before/after`, `chat_reranker_improved_total`을 기록합니다.
개인화가 실제 품질 개선인지 수치로 확인할 수 있습니다.

## 로컬 검증 지표
- `chat_memory_total{result}`
- `chat_pref_profile_total{result}`
- `chat_pref_feedback_total{result}`
- `chat_reranker_improved_total{improved}`

## 리스크와 완화
### 리스크 1) profile drift
오래된 선호가 과도하게 유지될 수 있습니다.
완화: TTL + explicit feedback overwrite.

### 리스크 2) 과도한 재정렬
개인화 점수가 근거 품질을 압도하면 결과 왜곡이 생깁니다.
완화: base score + category/tag 가중치 밸런스 제한.

## 개선 과제
- profile decay(시간 가중 감쇠) 도입
- A/B로 rerank gain 검증 자동화
- 세션 간 cold-start 전략 고도화

대화 기억과 취향은 "답변 생성기"가 아니라 "정렬기"로 붙일 때 안정성과 품질을 함께 얻을 수 있습니다.
