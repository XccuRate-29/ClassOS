# Solver service protocol

The C++ service (`core/server`) exposes two endpoints. It has no authentication, so it
must only be reachable by the backend (localhost or a private Docker network).

## `GET /health`

`{"status":"UP"}`

## `POST /v1/timetable/solve`

Days and periods are 1-based. `day` uses ISO numbering (1 = Monday).

```json
{
  "days": 5,
  "periodsPerDay": 8,
  "breakAfter": [4],
  "timeLimitMs": 10000,
  "rooms":  [{"id": 1, "capacity": 70, "type": "CLASSROOM"}],
  "events": [{"id": 1, "offeringId": 12, "teacherId": 5, "sectionId": 3,
              "size": 60, "kind": "LECTURE", "length": 1}],
  "fixed":  [{"roomId": 1, "teacherId": 5, "sectionId": 3, "day": 1, "startPeriod": 2, "length": 1}],
  "teacherUnavailable": [{"teacherId": 5, "day": 1, "period": 1}]
}
```

- `type`: `CLASSROOM`, `LAB`, `SEMINAR_HALL`. Lectures go in classrooms or seminar halls; labs in labs.
- `fixed`: occupancy the solver must avoid (manual and pinned slots). `0` = resource not used.
- `timeLimitMs` is clamped to 100–30000; `nodeLimit` (optional) to 1–20,000,000.
- `variableOrder` (optional): `MRV` (default) or `INPUT`, a baseline used only for evaluation.

Response:

```json
{
  "complete": false,
  "placements": [{"eventId": 1, "day": 2, "startPeriod": 1, "roomId": 1}],
  "unplaced":   [{"eventId": 7, "reason": "no lab holds 80 students (largest holds 65)"}],
  "violations": [],
  "stats": {"nodes": 89, "backtracks": 0, "elapsedMs": 3, "limitReached": false}
}
```

`violations` is the result of an independent re-check (`validate()`); it should always be
empty, and the backend refuses to save anything if it is not.

Errors: `400 {"error": "..."}` for malformed input, `500` for unexpected failures.

## Algorithm summary

Constraint satisfaction with backtracking. Variable order: fewest currently feasible
positions first (MRV, the DSatur idea), ties broken by most unplaced neighbours in the
conflict graph. Value order: soft-constraint score (spread a course across days, balance a
section's days, earlier periods, tightest room that fits). If the node or time budget runs
out, the deepest partial assignment found is completed greedily and remaining events are
reported with a reason. See `core/src/timetable.cpp`.

## `POST /v1/schedule` (grading queue)

Runs each requested policy on the same jobs. Times are minutes relative to "now".

```json
{
  "policies": ["FCFS", "PRIORITY", "ROUND_ROBIN"],
  "params": {"agingPerHour": 0.25, "priorityStep": 10, "quantum": 3, "start": 0},
  "jobs": [{"id": 41, "queue": 7, "arrival": -180, "burst": 15, "priority": 1, "deadline": 2880}]
}
```

Response:

```json
{
  "results": [
    {"policy": "FCFS",
     "order": [{"jobId": 41, "start": 0, "finish": 15}],
     "metrics": {"jobs": 1, "avgWait": 180, "maxWait": 180, "avgTurnaround": 195,
                 "deadlineMisses": 0, "fairness": 1.0}}
  ]
}
```

`quantum` is clamped to 1–100; at most 20,000 jobs. See `docs/evaluation-queue.md`.
