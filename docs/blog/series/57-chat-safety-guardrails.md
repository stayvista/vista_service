---
title: "StayVista 기술 개발기 57: Chat Safety Guardrails - 프롬프트 공격과 과도 확신 응답을 막는 다층 방어"
slug: "57-chat-safety-guardrails"
series: "StayVista 기술 개발기"
order: 57
prev_slug: "56-citation-verifier-grounding"
next_slug: "58-pii-redaction-pipeline"
status: "publish-ready"
excerpt: "LLM 품질 못지않게 중요한 건 안전성입니다. 입력/출력/증거를 각각 검증하지 않으면 검증 단계에서 큰 오류로 이어집니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 57: Chat Safety Guardrails - 프롬프트 공격과 과도 확신 응답을 막는 다층 방어

## 한 줄 요약
LLM 품질 못지않게 중요한 건 안전성입니다. 입력/출력/증거를 각각 검증하지 않으면 검증 단계에서 큰 오류로 이어집니다.

## 위협 모델
Chat 경로에서 실제로 다뤄야 하는 위협은 세 가집니다.

- 입력 공격: prompt injection, 정책 위반 요청
- 민감정보 유입: 이메일/전화/카드/주민번호 패턴
- 출력 과도확신: "확정/보장/100%" 같은 오버클레임

## 현재 방어 레이어
`ChatSafetyPolicy`는 다음을 수행합니다.

### 1) 입력 단계
- PII 포함 여부 차단
- prompt injection 패턴 차단
- 정책 금지 키워드 차단

### 2) 출력 단계
- injection 패턴 제거/차단
- 카드/소스 텍스트 sanitize
- followup 정제

### 3) Copilot 출력 특화
- 재고 근거 없이 "예약 가능" 단정 완화
- 정책 근거 없이 "무료 취소" 단정 완화
- 가격 근거 없는 금액 표현에 주의 문구 부착

## evidence 방어가 중요한 이유
응답 본문만 안전해도 evidence/source가 공격 문자열을 담고 있으면 UI/로그를 통해 재노출됩니다.
그래서 evidence 텍스트도 별도로 sanitize합니다.

## 관측 지표
- `chat_guardrails_block_total{reason=*}`
- `chat_copilot_guardrail_violation_total{reason=*}`
- `chat_copilot_guardrail_rewrite_total`

이 지표로 차단/완화 비율을 모니터링하고 정책 튜닝 우선순위를 정합니다.

## 기술적으로 중요한 포인트
### 1) "차단"과 "완화" 분리
모든 위험을 hard block으로 처리하면 UX가 급격히 나빠집니다.
입력은 차단, 출력은 완화/재서술 전략을 병행합니다.

### 2) 규칙은 코드로 고정
정책 문자열이 흩어지면 일관성이 깨집니다.
현재는 패턴/키워드를 정책 컴포넌트에 집중시켰습니다.

### 3) 안전정책은 성능정책과 충돌 가능
검증이 늘수록 지연이 증가할 수 있으므로 최소 비용 패턴부터 검사해야 합니다.

## 남은 과제
- 패턴 기반 외에 모델 기반 safety classifier 보강
- 다국어 공격 패턴 확장
- false positive/false negative 샘플 기반 정책 자동 튜닝

Guardrail은 모델 위에 덧씌운 장식이 아니라, 서비스 안전성을 보장하는 별도 실행 계층입니다.

