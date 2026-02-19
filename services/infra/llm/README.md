# Local LLM Infra (Ollama)

## 목적
StayVista AI 컨시어지의 로컬 LLM/임베딩을 내부망에서만 호출하도록 운영 표준을 정의합니다.

## 기본 구성
- chat model: `llama3.1:8b`
- embed model: `bge-m3`
- endpoint: `http://127.0.0.1:23434`

## 실행
```bash
./services/infra/llm/up.sh
```
(`compose.yaml`의 `llm`/`llm-init`와 `services/docker/docker-compose.yml`의 `ollama`/`ollama-init`는 동일 설정입니다.)

직접 기동:
```bash
docker compose -f services/docker/docker-compose.yml --profile llm up -d
```

애플리케이션 환경변수:
- `CHAT_LLM_ENABLED` (`false`면 LLM 호출 없이 RAG 템플릿 경로로 degrade)
- `LLM_BASE_URL` (default `http://127.0.0.1:23434`)
- `LLM_MODEL_CHAT` (default `llama3.1:8b`)
- `LLM_TIMEOUT_SOFT_MS` / `LLM_TIMEOUT_HARD_MS`
- `LLM_STREAMING_ENABLED`

## 헬스/레디니스
```bash
./services/infra/llm/healthz.sh
./services/infra/llm/readyz.sh
```
- backend probe:
  - `GET /internal/llm/healthz`
  - `GET /internal/llm/readyz`
- `readyz`는 모델 태그 확인 + 실제 generate/embed probe까지 통과해야 `ready`를 반환합니다.

## 워밍업
```bash
./services/infra/llm/warmup.sh
```
- cold/warm latency를 비교 출력합니다.

## 모델 교체/롤백
```bash
./services/infra/llm/swap-model.sh llama3.1:70b-instruct bge-m3
./services/infra/llm/swap-model.sh llama3.1:8b bge-m3
```
- `swap-model.sh`는 pull -> readyz -> warmup -> canary 순서로 검증합니다.

카나리 단독 실행:
```bash
./services/infra/llm/canary.sh llama3.1:70b-instruct
```

빠른 롤백(1분 내):
```bash
OLLAMA_PREV_CHAT_MODEL=llama3.1:70b-instruct \
  ./services/infra/llm/swap-model.sh llama3.1:8b bge-m3
```
