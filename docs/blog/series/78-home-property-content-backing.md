---
title: "StayVista 기술 개발기 78: [확장] 홈/숙소 콘텐츠 DB 백킹 - 정적 화면을 데이터 기반으로 전환한 구조"
slug: "78-home-property-content-backing"
series: "StayVista 기술 개발기"
order: 78
prev_slug: "77-locale-fx-price-pipeline"
next_slug: "79-promotion-coupon-claim-concurrency"
status: "publish-ready"
excerpt: "홈/숙소 화면을 코드 하드코딩으로 두면 수정 속도가 급격히 떨어집니다. StayVista는 홈 섹션과 숙소 상세 콘텐츠를 DB 테이블로 분리해 API 조합 방식으로 제공했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 78: [확장] 홈/숙소 콘텐츠 DB 백킹 - 정적 화면을 데이터 기반으로 전환한 구조

## 한 줄 요약
UI를 코드에 하드코딩하지 않고, 홈/숙소 콘텐츠를 테이블로 분해하면 화면 변경은 데이터 수정만으로 처리할 수 있습니다.

## 도입 배경
초기에는 홈 섹션과 숙소 상세 설명을 프론트 고정 데이터로 처리했습니다. 이 방식은 다음 문제가 있었습니다.

- 배너/섹션 순서 변경 때 코드 수정이 필요
- 숙소별 강조 문구/갤러리/요금 혜택 구조 확장 어려움
- 카드 구성이 API 응답과 분리돼 일관성 저하

그래서 `V14__home_and_property_content_db_backing.sql`로 콘텐츠 테이블을 분리했습니다.

## 홈 콘텐츠 API (`HomeContentService`)
`GET /v1/home/content`는 아래 테이블을 조합합니다.

- `home_hero`, `home_hero_metric`
- `home_quick_filter`
- `home_destination_card`
- `promotion_section`

구현 포인트:
- destination 카드의 `property_count`가 null이면 `property` 집계를 fallback으로 사용
- 섹션/정렬은 `display_order`로 고정

## 숙소 상세 콘텐츠 API (`PropertyContentService`)
`GET /v1/properties/{propertyId}/content`는 아래 묶음을 반환합니다.

1. editorial
- `property_editorial`

2. 시각/강조 요소
- `property_highlight`
- `property_gallery_image`

3. 스테이케이션 카드
- `property_staycation_card`
- `property_staycation_item`

4. 객실별 상세 구성
- `room_type_media`
- `room_type_feature`
- `room_rate_plan`
- `room_rate_plan_benefit`

핵심은 DB의 다단계 관계를 API에서 정규화해 프론트가 바로 렌더링 가능하도록 만든 점입니다.

## 기술적으로 중요한 포인트
### 1) room_type 단위 콘텐츠를 별도 계층으로 유지했습니다
숙소 공통 정보와 객실별 정보를 섞지 않아, room variant가 늘어도 구조가 깨지지 않습니다.

### 2) 카드/아이템을 별도 테이블로 분리했습니다
`card -> item` 구조로 분해해 카드 타입 확장과 정렬 변경을 데이터로 처리했습니다.

### 3) 조회 순서를 명시했습니다
각 테이블에 `display_order` + `active`를 두어 비활성/노출순서를 일관되게 유지합니다.

## 관련 API
- `GET /v1/home/content`
- `GET /v1/properties/{propertyId}/content`

기존 숙소 핵심 정보 API(`GET /v1/properties/{id}`)와 분리되어 있어서, 성격이 다른 데이터가 섞이지 않습니다.

## 남은 과제
- admin 편집 API 추가(현재는 DB 시드/직접 수정 중심)
- 콘텐츠 버전 스냅샷(롤백 포인트)
- 섹션별 A/B 실험 파라미터 연결
