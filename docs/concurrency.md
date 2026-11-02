# Concurrency design

## The problem

A timetable edit or room booking is a *check-then-act* sequence: read whether a room is
free, then write. Two concurrent requests can both read "free" and both write. This is
the same race condition as two threads updating shared memory without a lock.

## Layered defence

1. **Database constraints (always correct).** Exclusion constraints on `timetable_slots`
   (room, teacher, section) and `room_bookings` (room per date) make overlapping rows
   impossible, whatever the application does. A request that loses a race gets a
   constraint violation, which the API turns into `409` with a readable message.
2. **Row locks (correct and friendly).** Some rules span two tables (a booking must not
   overlap a *weekly* class), so no single constraint can express them. Those operations
   lock the room's row with `SELECT … FOR UPDATE` before checking, so requests for the same
   room take turns. This works like a mutex per room: different rooms still proceed in
   parallel.
3. **Java pre-checks (friendly only).** The service checks first so it can say *which*
   class clashes. On its own this would be unsafe; layers 1 and 2 make it safe.

## Lock ordering (deadlock prevention)

Deadlock needs a cycle of transactions waiting for each other. Acquiring locks in one
global order makes a cycle impossible (the resource-ordering technique from OS theory):

| Operation | Locks, in order |
|-----------|-----------------|
| Add / move a class | term row `FOR SHARE` → target room `FOR UPDATE` |
| Generate timetable | term row `FOR UPDATE` → all rooms `ORDER BY id FOR UPDATE` |
| Book a room | that room `FOR UPDATE` |

`FOR SHARE` on the term acts like a reader lock: many manual edits may run together,
while generation (the writer, `FOR UPDATE`) waits for them and blocks new ones until it
finishes. This is the readers–writers pattern.

## Evidence

- `BookingConcurrencyTest`: 8 threads book the same room and time at once; exactly one
  succeeds and seven receive `409`.
- `SchemaConstraintsTest` / `TimetableConflictTest`: overlaps are rejected by the database
  even when Java checks are bypassed.

## Known trade-off

Generation holds the term and room locks while the solver runs (bounded by
`SOLVER_TIME_LIMIT_MS`). During that time, bookings and edits wait. A background job that
solves first and then applies changes with a version check would avoid this.
