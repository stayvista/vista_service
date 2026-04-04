---
title: "StayVista 기술 개발기 99: [심화] DomainSupportService 패턴 - 공통 검증과 Outbox 기록 표준화"
slug: "99-domain-support-outbox-helpers"
series: "StayVista 기술 개발기"
order: 99
prev_slug: "98-promotion-claim-race-control"
next_slug: "100-search-request-normalization-placeid"
status: "publish-ready"
excerpt: "도메인 서비스마다 사용자/파트너 검증과 outbox insert를 반복하면 실수가 늘어납니다. StayVista는 `DomainSupportService`로 공통 검증과 이벤트 기록 방식을 표준화해 코드 중복을 줄였습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 99: [심화] DomainSupportService 패턴 - 공통 검증과 Outbox 기록 표준화

## 한 줄 요약
공통 로직을 서비스마다 복사하면 도메인별로 미묘하게 다른 규칙이 생깁니다. 공통 보조 서비스로 기준을 고정하면 정합성 유지가 쉬워집니다.

## 핵심 책임
`DomainSupportService`는 4가지 공통 책임을 가집니다.

- `ensureUserExists`
- `getActiveUser`
- `ensurePartnerExists`
- `appendOutbox`

## 사용자 검증
`ensureUserExists`는 `user_account(status='ACTIVE')` 존재 여부를 COUNT로 확인합니다.

없으면 `UNAUTHORIZED`를 던져 도메인별로 서로 다른 에러 코드를 반환하는 문제를 줄였습니다.

`getActiveUser`는 조회 결과를 `UserAccount`로 반환해 read-model 서비스에서 재사용합니다.

에러 메시지(`User not found or inactive`)도 고정돼 있어, 인증 실패 케이스를 로그에서 일관되게 검색할 수 있습니다.

## 파트너 upsert
`ensurePartnerExists`는
`INSERT ... ON DUPLICATE KEY UPDATE` 패턴으로 파트너 존재를 보장합니다.

관리자 입력이 완전하지 않은 로컬 개발 단계에서 카탈로그 생성 흐름을 단순화하는 역할을 했습니다.

## Outbox 표준 기록
`appendOutbox`는 다음 값을 고정합니다.

- `event_id`: UUID
- `aggregate_type`, `aggregate_id`, `event_type`
- `payload_json`: ObjectMapper 직렬화
- `status='NEW'`

실제 SQL은 `payload_json`을 `CAST(? AS JSON)`으로 저장해 JSON 타입 무결성을 DB에서 한번 더 검증합니다.

이 함수 하나로 도메인별 outbox insert SQL 중복을 제거했습니다.

## 적용 효과
- Booking/Ticket/Package/Promotion 등에서 이벤트 기록 방식 일치
- 이벤트 누락/컬럼 누락 같은 단순 실수 감소
- OutboxRelayJob 처리 전제(NEW 상태)와 바로 맞물림

## 경계와 한계
공통 보조 서비스는 편리하지만 책임이 과도해지면 역으로 결합도가 높아집니다.

그래서 현재 구현은 "검증/기록"만 담당하고, 도메인 상태 전이나 정책 결정은 각 서비스에 남겨 둔 상태입니다.

## 기술적으로 중요한 포인트
- 공통 검증은 "편의"가 아니라 도메인 오류 코드 일관성을 위한 장치입니다.
- outbox 기록 포맷이 흔들리면 소비자에서 예외 케이스가 폭증합니다.
- 보조 서비스는 얇게 유지하고, 도메인 의사결정은 각 서비스에 남겨야 균형이 맞습니다.
- JSON cast 실패를 조기에 노출하면 잘못된 payload를 소비자 단계까지 보내지 않을 수 있습니다.

## 남은 과제
- `appendOutbox` payload schema version 추가
- 공통 검증 실패 지표 계측
- 도메인별 aggregate naming 규칙 문서화
