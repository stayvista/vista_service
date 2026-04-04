---
title: "StayVista 기술 개발기 98: [심화] 프로모션 쿠폰 발급 경합 제어 - 중복/소진 경계 처리"
slug: "98-promotion-claim-race-control"
series: "StayVista 기술 개발기"
order: 98
prev_slug: "97-voucher-issue-outbox-consumer"
next_slug: "99-domain-support-outbox-helpers"
status: "publish-ready"
excerpt: "쿠폰 발급은 사용자 중복 요청과 잔여 수량 경쟁이 동시에 발생합니다. StayVista는 UNIQUE 제약, 예외 복구 분기, 조건부 카운터 업데이트를 조합해 경합 경계를 처리했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 98: [심화] 프로모션 쿠폰 발급 경합 제어 - 중복/소진 경계 처리

## 한 줄 요약
프로모션 claim은 "중복 발급 방지"와 "수량 초과 방지"를 동시에 만족해야 합니다. 둘 중 하나만 맞춰도 정합성이 깨집니다.

## 스키마 기반 안전장치
`V12__home_promotions_coupon.sql` 기준으로 아래 제약을 사용합니다.

- `uk_promotion_claim_campaign_user (campaign_id, user_id)`
- `uk_promotion_claim_coupon_code (coupon_code)`

그리고 캠페인 잔여 수량은 `issue_limit - issued_count`로 계산합니다.

## claim 트랜잭션 (`PromotionService.claimCampaign`)
1. 사용자 활성 검증
2. 캠페인 상태/기간 검증 (`ACTIVE`, 시작~종료 시각)
3. 기존 claim 조회(이미 발급 시 재사용 응답)
4. claim INSERT 시도
5. 캠페인 카운터 조건부 UPDATE
   - `issued_count < issue_limit` 조건 포함
6. 성공 시 outbox `coupon_claimed` 기록

메서드 전체는 `@Transactional` 경계에 있으므로, 중간 단계에서 `DomainException`이 발생하면 claim INSERT와 카운터 UPDATE가 함께 롤백됩니다.

## DuplicateKey 복구 분기
INSERT에서 `DuplicateKeyException`이 발생하면 즉시 기존 claim을 다시 조회해 이미 발급 응답으로 전환합니다.

즉 동시 요청에서도 사용자 입장에서는 중복 에러 대신 안정된 응답을 받습니다.

## 소진 경계
claim row가 만들어졌더라도 카운터 UPDATE가 0행이면 소진으로 판단해 `CONFLICT`를 반환합니다.

중요한 점은 이 예외가 트랜잭션 롤백을 유도하므로, 소진 케이스에서 고아 claim row가 남지 않는다는 것입니다.

## 쿠폰 코드 생성 규칙
`buildCouponCode`는 다음 규칙으로 코드를 만듭니다.

- prefix: 캠페인 코드의 영숫자만 추출 후 뒤 6자리
- suffix: UUID 기반 8자리 대문자
- 최종 형식: `<PREFIX>-<SUFFIX>`

즉 사용자 중복 방지는 `(campaign_id, user_id)` 유니크 키가 담당하고, 쿠폰코드 충돌은 별도 유니크 키가 한 번 더 방어합니다.

## 목록 조회의 지역/국가 필터
`listCampaigns`는 section/city/excludeCountry를 조합합니다.

- city가 있으면 city 일치 또는 NULL
- excludeCountry가 있으면 해당 국가 property가 있는 도시를 제외
- `limit`는 `1..60` 보정

추가로 `status IN ('ACTIVE', 'PAUSED')`와 `ends_at >= NOW(3)-1day` 조건을 걸어 최근 종료 캠페인까지 짧게 노출할 수 있도록 했습니다.

## 테스트 근거
`PromotionServiceTest`
- section normalize(`global_pick -> GLOBAL_PICK`)
- `excludeCountry` 대문자 정규화
- city 필터 파라미터 전달 검증

## 계측
- `promotion_campaign_list_total{section,has_city_filter,exclude_country}`
- `promotion_claim_total{result=success|already_claimed|sold_out|inactive|out_of_window}`

결과 태그를 세분화해 실패 원인을 빠르게 분기할 수 있게 했습니다.

## 기술적으로 중요한 포인트
- 사용자 중복은 UNIQUE 제약으로, 잔여 수량은 조건부 UPDATE로 각각 막아야 합니다.
- DuplicateKey를 단순 실패로 처리하지 않고 복구 분기로 바꿔야 UX가 안정됩니다.
- outbox 이벤트를 같이 기록해야 이후 쿠폰 후속 처리와 일관성이 맞습니다.
- 트랜잭션 롤백 경계를 명확히 두면 경합 실패 시 데이터 정리를 별도 배치에 의존하지 않아도 됩니다.

## 남은 과제
- claim/카운터 증가 단일 SQL화 검토
- 캠페인 행 잠금(`SELECT ... FOR UPDATE`) 적용 기준 정의
- 다중 캠페인 동시 claim 정책
