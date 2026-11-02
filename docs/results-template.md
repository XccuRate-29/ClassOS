# Results (fill in from your own measurements)

Every cell must come from a run you did. Record the date, git commit and hardware with
each table. Leave a cell empty rather than estimating.

## Environment

| Item | Value |
|------|-------|
| Date | |
| Git commit | |
| Server machine (CPU, RAM, OS) | |
| k6 on the same machine? | |
| `DB_POOL_SIZE` | |

## Automated tests

| Suite | Tests | Passed | Notes |
|-------|-------|--------|-------|
| C++ (ctest) | | | |
| Java (mvn test) | | | |
| Frontend build | | | |
| Smoke test (`scripts/smoke.sh`) | | | |

## Load tests (median of 3 runs)

| Test | Load | Requests | p50 (ms) | p95 (ms) | p99 (ms) | Errors | Correctness check | All thresholds passed |
|------|------|----------|----------|----------|----------|--------|-------------------|-----------------------|
| Attendance burst | 60 students | | | | | | present = | |
| Attendance burst | 120 students | | | | | | present = | |
| Booking contention | 40 teachers | | | | | | bookings stored = | |
| Read mix | 100 users | | | | | | | |

## Performance refinement (before / after)

| Change | Test | Before p95 | After p95 | Other effect |
|--------|------|-----------|----------|--------------|
| | | | | |

## Timetable generation

From `core/build/classos_bench` (see `docs/benchmarks.md`) and the admin Generate button.

| Data set | Classes | Placed | Search steps | Backtracks | Time (ms) |
|----------|---------|--------|--------------|------------|-----------|
| Sample department (MRV ordering) | | | | | |
| Sample department (input-order baseline) | | | | | |
| Heavy teacher load, 20 sections (MRV) | | | | | |
| Heavy teacher load, 20 sections (input-order) | | | | | |
| Larger department | | | | | |

## Grading queue policies (same queue)

Use a real teacher's queue from the trial (Grading page, comparison table) and the two
scenarios in `classos_bench`.

| Policy | Parameters | Avg wait (h) | Longest wait (h) | Past grade-by | Fairness |
|--------|------------|--------------|------------------|---------------|----------|
| FCFS | | | | | |
| Priority | aging = | | | | |
| Round Robin | quantum = | | | | |

## Trial versus baseline

| Measure | Before ClassOS | With ClassOS | How measured |
|---------|----------------|--------------|--------------|
| Time to take attendance (class of 60) | | | |
| Timetable clashes this term | | | |
| Room booking conflicts | | | |
| Proxy attempts detected (expired code, shared phone) | n/a | | `attendance_attempts` |

## Usability (System Usability Scale)

| Group | Responses | Average SUS (0-100) |
|-------|-----------|---------------------|
| Students | | |
| Teachers | | |
| Lab assistants and admins | | |
| All | | |

## Feedback themes

| Theme | Mentions | Example (paraphrased, anonymous) | Action taken |
|-------|----------|----------------------------------|--------------|
| | | | |
