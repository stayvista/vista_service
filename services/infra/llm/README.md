# Local LLM Infra (Ollama)

## 목적
StayVista AI 컨시어지의 로컬 LLM/임베딩을 내부망에서만 호출하도록 운영 표준을 정의합니다.

## 기본 구성
- chat model: `llama3.1:8b-instruct`
- embed model: `bge-m3`
- endpoint: `http://127.0.0.1:21434`

## 실행
```bash
./services/infra/llm/up.sh
```
(`compose.yaml` 단일 파일의 `llm`/`llm-init` 서비스를 사용합니다.)

## 헬스/레디니스
```bash
./services/infra/llm/healthz.sh
./services/infra/llm/readyz.sh
```
- backend probe:
  - `GET /internal/llm/healthz`
  - `GET /internal/llm/readyz`

## 워밍업
```bash
./services/infra/llm/warmup.sh
```
- cold/warm latency를 비교 출력합니다.

## 모델 교체/롤백
```bash
./services/infra/llm/swap-model.sh llama3.1:70b-instruct bge-m3
./services/infra/llm/swap-model.sh llama3.1:8b-instruct bge-m3
```
