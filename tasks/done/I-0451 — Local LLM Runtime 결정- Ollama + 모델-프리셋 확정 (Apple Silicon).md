# I-0451 — Local LLM Runtime 결정: Ollama + 모델/프리셋 확정 (Apple Silicon)

## Goal
M2 Max(Apple Silicon) 환경에서 안정적으로 동작하는 로컬 LLM 런타임/모델 조합을 확정하고, 로컬/스테이징 표준 실행을 만든다.

## Decision (초기 기본안)
- Runtime: **Ollama** (gguf + Metal 가속)
- Chat Model 후보(초기):
  - `qwen2.5:7b-instruct` 또는 `llama3.1:8b-instruct`
- Embedding Model:
  - `bge-m3`
- 운영 원칙:
  - cold start 완화: **warmup 필수**
  - 모델 교체/롤백 1분 내 가능하도록 스크립트화

## Deliverables
- `services/docker/docker-compose.yml`에 `ollama` 서비스 추가(DEV)
- `services/infra/llm/README.md`
  - 모델 pull, warmup, 포트/환경변수, 장애 대응
- `RUNBOOK.md`에
  - warmup/ready/health 확인 절차
  - 모델 교체/롤백 절차

## Implementation Notes
- Ollama port: `11434`(기본) 또는 레포 표준 포트(예: `21434`)로 통일
- readiness는 "모델 pull + warmup 완료"를 반영하도록 구성

## Acceptance Criteria
- `curl`로 `/api/tags` 또는 `/api/generate` 호출 시 응답 확인
- warmup 이후 첫 토큰/첫 응답 지연이 감소하는지 로그로 확인
