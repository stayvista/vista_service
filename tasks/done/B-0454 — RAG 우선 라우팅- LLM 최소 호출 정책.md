# B-0454 — RAG 우선 라우팅: LLM 최소 호출 정책

## Goal
M2 Max 단일 노드에서 LLM이 병목이 되지 않도록, LLM은 필요할 때만 호출한다.

## Routing Policy
- Step 1: Slot Extract (룰/사전) → context 부족 시 clarification (LLM 없이)
- Step 2: Retrieve (RAG) → 결과 충분하면 template 응답 (LLM 없이)
- Step 3: 일정/비교/긴 문장 등 “서술 필요” 시에만 LLM

## Outputs
- `llm_used` 플래그 반드시 노출
- `debug.route = CLARIFY|TEMPLATE|LLM` (dev only)

## Metrics
- `llm_used_rate`
- `route_clarify_rate`, `route_template_rate`, `route_llm_rate`

## Acceptance Criteria
- LLM OFF에서도 서비스가 기능적으로 동작
- LLM 사용률이 목표(예: 20~40%)로 유지되도록 조절 가능
