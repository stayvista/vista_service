# Loadtest (k6)

## Prerequisites
- k6 installed
- local API running on `http://localhost:8080`
- seed data prepared:
```bash
./services/tools/seed/run_seed.sh bulk
```

## Scenarios
1. Search steady
```bash
k6 run services/loadtest/k6/search.js
```
2. Booking hold spike
```bash
ROOM_TYPE_ID=1 CHECK_IN=2026-02-10 CHECK_OUT=2026-02-12 k6 run services/loadtest/k6/booking_hold.js
```
3. Full funnel
```bash
ROOM_TYPE_ID=1 k6 run services/loadtest/k6/full_funnel.js
```

## Queue before/after comparison
- Queue OFF: `stayvista.queue.enabled=false`
- Queue ON: `stayvista.queue.enabled=true`
- Compare `http_req_duration`, `http_req_failed`, and 429 ratio.
