---
title: "StayVista 기술 개발기 77: [확장] Locale + FX 파이프라인 - 국가/언어 추론과 가격 통화 변환"
slug: "77-locale-fx-price-pipeline"
series: "StayVista 기술 개발기"
order: 77
prev_slug: "76-auth-session-guardrails"
next_slug: "78-home-property-content-backing"
status: "publish-ready"
excerpt: "Locale 추론과 환율 변환을 분리하지 않으면 가격 응답이 쉽게 흔들립니다. StayVista는 locale 우선순위와 FX fallback 체인을 분리해 일관된 가격 응답을 유지했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 77: [확장] Locale + FX 파이프라인 - 국가/언어 추론과 가격 통화 변환

## 한 줄 요약
사용자 locale 결정과 환율 계산은 별도 레이어로 분리해야, 검색/가격캘린더/상세 페이지에서 통화 응답이 일관되게 유지됩니다.

## Locale 결정 우선순위 (`LocaleService`)
`GET /v1/locale`는 아래 순서로 locale을 결정합니다.

1. `user_locale` 저장값
2. `session_locale` 저장값
3. 헤더 기반 추론(`X-Country-Code`, `Accept-Language`)

이 순서를 고정해 두면, 로그인 전/후 동작 차이를 예측하기 쉬워집니다.

## 세션 식별자 전략
`X-Anon-Id`가 없으면 `remoteAddr + User-Agent` 해시로 `anon_<hash>`를 생성합니다.

덕분에 비로그인 상태에서도 locale override를 세션 단위로 저장할 수 있습니다.

## Locale 수동 변경
`POST /v1/locale`는 아래를 upsert합니다.

- 로그인 사용자: `user_locale`
- 익명 사용자: `session_locale`

검증 포인트:
- country 길이 2~3
- currency 길이 3~5
- language 미입력 시 `Accept-Language` 또는 국가 기반 기본값 사용

## FX 환율 해석 체인 (`FxService`)
`quote(base, quote)`는 다음 순서로 환율을 찾습니다.

1. direct rate (`fx_rate`)
2. inverse rate 역수
3. KRW 경유 체인 (`base -> KRW -> quote`)
4. 정적 fallback map

모든 체인이 실패해도 최종 fallback으로 응답을 유지합니다.

## 캐시/정밀도 처리
- FX quote 캐시: 300초
- 금액 변환: `HALF_UP` 반올림
- `base == quote`면 즉시 1.0 반환

이는 로컬 환경에서 DB/캐시 부하를 줄이면서도 가격 응답 흔들림을 줄이기 위한 선택입니다.

## Price Calendar와의 연결
`PriceCalendarService`는 `city_day_min_price`(KRW 기준)를 읽고, 응답 직전에 `FxService.convert()`를 적용합니다.

즉 저장 통화와 노출 통화를 분리했습니다.

## 기술적으로 중요한 포인트
### 1) locale과 fx를 강결합하지 않았습니다
Locale은 사용자 선호의 문제이고, FX는 계산 규칙의 문제라서 책임이 다릅니다.

### 2) fallback 체인을 명시했습니다
환율 데이터가 비어도 응답이 깨지지 않도록 실패 경로를 코드로 고정했습니다.

### 3) place 타입별 가격 소스를 분리했습니다
- property: room_type 최소가
- city/poi: `city_day_min_price` 우선, 없으면 city fallback

## 관련 스키마
- `session_locale`, `user_locale`
- `fx_rate`
- `city_day_min_price`

위 테이블은 `V10__search_parity_foundation.sql`에 포함되어 있습니다.

## 남은 과제
- 외부 환율 소스 동기화 배치
- 통화별 소수점 정책(통화별 스케일) 분리
- locale별 라벨/문구 국제화 확장
