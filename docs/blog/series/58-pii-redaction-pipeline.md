---
title: "StayVista 기술 개발기 58: PII Redaction Pipeline - 민감정보를 저장/로그/실험 경로에서 제거하는 방법"
slug: "58-pii-redaction-pipeline"
series: "StayVista 기술 개발기"
order: 58
prev_slug: "57-chat-safety-guardrails"
next_slug: "59-shadow-run-evaluation"
status: "publish-ready"
excerpt: "PII는 \"나중에 필터링\"이 아니라 \"처음부터 유입/저장을 제어\"해야 합니다. 특히 shadow/실험 경로에서 누락되기 쉽습니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 58: PII Redaction Pipeline - 민감정보를 저장/로그/실험 경로에서 제거하는 방법

## 한 줄 요약
PII는 "나중에 필터링"이 아니라 "처음부터 유입/저장을 제어"해야 합니다. 특히 shadow/실험 경로에서 누락되기 쉽습니다.

## 현재 PII 탐지 대상
`PiiRedactor`는 정규식 기반으로 다음을 탐지/치환합니다.

- 이메일
- 휴대폰 번호
- 카드 번호(13~19자리 패턴)
- 주민등록번호 패턴

치환 토큰:
- `[REDACTED_EMAIL]`
- `[REDACTED_PHONE]`
- `[REDACTED_CARD]`
- `[REDACTED_RRN]`

## 파이프라인 적용 지점
### 1) 입력 안전정책
`ChatSafetyPolicy.evaluateInput()`에서 PII 포함 요청을 차단합니다.

### 2) shadow 실험 저장
`ChatShadowService`는 request/response를 저장하기 전에 redaction합니다.
즉 실험 데이터 테이블에는 원문이 아니라 redacted 문자열이 저장됩니다.

### 3) 보조 경로 전파 제어
프롬프트/근거/샘플 저장 경로도 함께 점검해야 누락이 줄어듭니다.

## 기술적으로 중요한 포인트
### 1) 탐지와 마스킹을 분리하지 말 것
탐지만 하고 저장 단계에서 실수하면 바로 유출됩니다.

### 2) shadow/analytics 경로가 더 위험
서비스 본문보다 실험/로그 경로에서 보호가 누락되기 쉽습니다.

### 3) false positive 허용 범위 정의
보안 기준에 따라 차단 강도를 높이면 오탐이 늘 수 있습니다.
어느 정도를 허용할지 정책적으로 합의가 필요합니다.

## 로컬 검증 지표/검토 포인트
- safety block 중 pii 사유 비율
- shadow sample에서 redaction 누락 샘플 검사
- 개발 로그 raw payload 포함 여부 정기 점검

## 개선 과제
- 국제 전화번호/국가별 PII 패턴 확장
- 문맥 기반 탐지(단순 regex 한계 보완)
- 저장소 레벨 암호화/마스킹 정책 통합

PII 파이프라인은 기능 품질과 직접 상충할 수 있지만, 개발 신뢰성의 최소선입니다. 특히 AI 기능에서는 반드시 독립 트랙으로 관리해야 합니다.

