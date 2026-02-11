# Eval Harness v2 (B-0521)

Golden dataset 기반으로 chat recommend 품질을 자동 채점합니다.

## Metrics
- slot_accuracy
- citation_coverage
- safety_violation_rate
- route_stability
- latency p95 / p99

## Datasets
- `services/eval/datasets/smoke_seed.json` (12 base cases)
- `services/eval/datasets/full_seed.json` (12 base cases, repeat로 500+ 확장)

## Run
```bash
# smoke: 12 x 3 = 36 cases
./gradlew evalSmoke

# full: 12 x 50 = 600 cases
./gradlew evalFull
```

Optional args (direct runner):
```bash
./gradlew runEval --args="--mode smoke --dataset services/eval/datasets/smoke_seed.json --repeat 3 --base-url http://localhost:18765"
```

## Reports
- JSON: `services/eval/reports/<mode>/eval_report_*.json`
- HTML: `services/eval/reports/<mode>/eval_report_*.html`

실패 시 threshold 위반과 케이스별 failure reason을 stdout에 함께 출력합니다.
