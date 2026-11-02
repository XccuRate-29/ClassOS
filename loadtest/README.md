# Load tests (Milestone 5)

[k6](https://k6.io) scripts for the three situations most likely to break ClassOS.
Each script checks **correctness** as well as speed: a run that is fast but records two
bookings, or loses an attendance mark, fails.

| Script | Situation | Pass criteria |
|--------|-----------|---------------|
| `attendance-burst.js` | A class of N students (default 60) scans the QR code at once | every mark accepted; p95 mark < 500 ms; exactly N present after closing |
| `booking-contention.js` | N teachers (default 40) book one room for the same hour at once | exactly 1 booking created, all others 409, no other errors; p95 < 1 s |
| `read-mix.js` | Up to N users (default 100) browsing timetables, courses and queues | < 1% errors; p95 < 300 ms per page |

## Before you run

1. **Use a separate database.** The first two scripts create users, a section, a room and
   bookings through the API, and leave them behind. Run against a throwaway stack, e.g.
   `docker compose -p classos-loadtest --profile full up -d --build` with its own `.env`,
   or reset the volume afterwards (`docker compose -p classos-loadtest down -v`).
2. A term must be marked current. With `DEV_SEED_ENABLED=true` this is done for you, and
   `read-mix.js` needs the seed accounts anyway.
3. Leave `ATTENDANCE_ALLOWED_CIDRS` empty for the attendance test, or run k6 from inside that range.
4. Run k6 on a different machine from the server if you can; on the same laptop, k6 and
   ClassOS compete for CPU and the numbers are pessimistic. Either way, **record the hardware**.

## Run

```bash
export BASE_URL=http://localhost:8088
export ADMIN_EMAIL=admin@classos.local ADMIN_PASSWORD='...' DEV_SEED_PASSWORD='...'

k6 run --summary-export=results/attendance-60.json  attendance-burst.js
k6 run -e STUDENTS=120 --summary-export=results/attendance-120.json attendance-burst.js
k6 run --summary-export=results/booking-40.json     booking-contention.js
k6 run -e VUS=100 --summary-export=results/read-100.json read-mix.js
```

PowerShell: `$env:BASE_URL="http://localhost:8088"` and so on, then the same `k6 run` lines.

## What to record for the report

For each run: date, git commit, hardware (CPU, RAM), whether k6 ran on the same machine,
`DB_POOL_SIZE`, and from the k6 summary: requests, p50/p95/p99 latency per tagged request,
error rate, and whether every threshold passed. `docs/results-template.md` has the tables.

## When a threshold fails

1. Look at which request is slow (k6 tags: `mark`, `code`, `book`, `timetable`, ...).
2. Check the backend log and `EXPLAIN ANALYZE` the queries behind that endpoint.
3. Change **one** thing (an index, `DB_POOL_SIZE`, a query), re-run the same test, and
   record before and after. That before/after table is the "performance refinement"
   evidence for Milestone 5.

Note: sign-in uses BCrypt with cost 12 on purpose (it slows password guessing), so the
setup phase spends a few seconds signing in test users. That time is not part of the
measured scenarios.
