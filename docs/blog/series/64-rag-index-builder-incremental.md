---
title: "StayVista 기술 개발기 64: [핵심] RAG Index Builder - Full/Incremental 빌드와 해시 기반 동기화"
slug: "64-rag-index-builder-incremental"
series: "StayVista 기술 개발기"
order: 64
prev_slug: "63-handoff-advisor"
next_slug: "65-hybrid-ranker-curation"
status: "publish-ready"
excerpt: "RAG 품질은 검색 알고리즘보다 먼저 \"인덱스 최신성\"에서 무너집니다. StayVista는 `RagIndexBuilderService`로 full/incremental 빌드를 분리하고 hash skip으로 비용을 제어합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 64: [핵심] RAG Index Builder - Full/Incremental 빌드와 해시 기반 동기화

## 한 줄 요약
RAG 품질은 검색 알고리즘보다 먼저 "인덱스 최신성"에서 무너집니다. StayVista는 `RagIndexBuilderService`로 full/incremental 빌드를 분리하고 hash skip으로 비용을 제어합니다.

## 인덱싱 대상과 저장 모델
소스는 4개 도메인에서 수집합니다.

- `PROPERTY`
- `TICKET`
- `PACKAGE`
- `POI`

저장 스키마:
- `travel_doc` (문서 메타 + 본문 + doc_hash)
- `travel_doc_chunk` (chunk_order/chunk_hash)
- `travel_doc_vec` (model별 vector_blob)

핵심은 문서/청크 모두 hash를 가지고 변경 여부를 판단한다는 점입니다.

## 빌드 모드
### FULL
- 전체 소스 스캔
- 필요 시 stale 문서 제거
- full 기준 elapsed를 baseline으로 저장

### INCREMENTAL
- source_type별 `latestIndexedAt` 이후만 조회
- 변경 없는 문서는 skip
- scheduler로 주기 실행 (`incremental-sync-ms`)

## 변경 감지 전략
### 문서 레벨
`SourceDoc.hash`와 기존 `travel_doc.doc_hash` 비교:
- 동일하면 문서 skip
- 다르면 document upsert + chunk/vector 갱신

### 청크 레벨
`chunk_text` SHA-256과 기존 `chunk_hash` 비교:
- 동일 청크는 embed 생략
- 변경 청크만 임베딩/벡터 upsert
- 초과된 old chunk는 vec -> chunk 순서로 정리

이 방식이 임베딩 비용을 크게 줄입니다.

## 기술적으로 중요한 포인트
### 1) incremental은 "빠른 전체 재색인"이 아닙니다
정확한 변경 감지가 있어야만 의미가 있습니다.
해시 없이 updated_at만 보면 누락/과잉 업데이트가 쉽게 발생합니다.

### 2) stale 정리는 full에서만 강하게 수행
개발 중 incremental에서 과한 삭제를 허용하면 데이터 손실 위험이 커집니다.
현재는 `mode=FULL && limit=null`일 때만 stale delete를 수행합니다.

### 3) 임베딩 실패는 전체 실패로 전이하지 않는다
`embedClient.embed()` 실패 시 `chat_rag_index_embed_fail_total`만 증가시키고 진행합니다.
인덱싱 파이프라인의 회복력을 높이는 선택입니다.

## 로컬 검증 지표
- `chat_rag_index_total{mode,result}`
- `chat_rag_index_ms{mode}`
- `chat_rag_incremental_speedup`
- `chat_rag_index_embed_fail_total`

증분 빌드의 목적은 "속도"보다 "정기 동기화 성공률"입니다.

## 장애 재현 시나리오
### 시나리오 1) 임베딩 서버 지연
- 증상: vectors_upsert 급감, embed_fail 증가
- 대응: incremental 주기 유지 + fallback retrieval(lexical) 품질 모니터링

### 시나리오 2) 소스 스키마 변경
- 증상: doc_hash 대량 변경, full build 시간 급증
- 대응: 한시적 limit 재조정, 야간 full build로 분산

## 개선 과제
- chunk 크기/overlap 자동 튜닝
- source_type별 증분 큐 분리
- build 결과 diff 리포트 자동 생성

RAG 빌더의 본질은 "새 모델"이 아니라 "변경된 데이터만 정확히 반영하는 능력"입니다.
