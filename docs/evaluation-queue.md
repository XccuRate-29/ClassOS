# Assignment evaluation queue (Milestone 4)

## The idea

A teacher with many ungraded submissions across several courses has to decide what to
grade next. This is a scheduling problem, so ClassOS orders the queue with CPU-scheduling
algorithms, computed in the C++ core (`core/src/scheduler.cpp`).

| CPU scheduling | Evaluation queue |
|----------------|------------------|
| Process / job | One ungraded submission |
| Arrival time | When the student submitted |
| Burst time | The assignment's "minutes to grade one" estimate |
| Priority | The assignment's grading priority (high, normal, low) |
| Ready queues | One queue per assignment |
| Deadline | The assignment's grade-by date |
| Waiting time | How long the student waits before grading starts |

## The policies

All three are **non-preemptive**: grading one submission is never paused halfway.

| Policy | Rule | Strength | Weakness |
|--------|------|----------|----------|
| FCFS | Oldest submission first | Simple, predictable, no starvation | A large assignment makes every other class wait (convoy effect) |
| Priority with aging | Highest `(4 - priority) × 10 + aging × hours waited` first | Important work first | Without aging, low-priority work can starve |
| Round Robin | Take up to *quantum* submissions from each assignment in turn | Every class sees progress | Total work is unchanged, so some individuals wait longer |

**Round Robin here is not time slicing.** Classic Round Robin preempts a process when its
time quantum expires. Grading cannot be preempted, so the quantum is a *count of
submissions per assignment per turn*, which is closer to round-robin between queues
(as in fair queueing) than to time slicing. State this clearly in the viva.

**Aging.** With aging switched off, a low-priority submission can wait indefinitely while
high-priority work keeps arriving (starvation). Each hour of waiting adds `agingPerHour`
points; with the default 0.25 and a 20-point gap between high and low, a low-priority
submission overtakes new high-priority ones after 80 hours. `test_aging_prevents_starvation`
demonstrates this.

## Metrics

The queue is simulated from now, grading one submission after another using each
assignment's estimated minutes:

- **Average and longest wait:** start time minus submission time.
- **Past grade-by date:** submissions that would be finished after their deadline.
- **Fairness:** Jain's index over each assignment's average wait,
  `(Σx)² / (n · Σx²)`, between `1/n` and 1; 1 means every class waits equally long.

A lesson from building it: the first version measured fairness over *turnaround* time.
A test showed that metric preferring FCFS in a case where Round Robin clearly helped a
small assignment, because equal turnaround is not what Round Robin provides. The metric was
changed to average *wait* per assignment, and tested on the case Round Robin is designed for
(several classes competing for the grader). Choosing the metric is part of the evaluation.

## Concurrency and safety

- **Resubmission while grading:** each submission has a `version`. A grade is saved only
  if the version the teacher downloaded is still current (optimistic locking); otherwise
  the teacher gets a 409 and downloads the new file.
- **Two tabs resubmitting at once:** the submission row is locked `FOR UPDATE`.
- **Scheduler unavailable:** the queue falls back to FCFS computed in Java and says so,
  so grading never stops.

## Reference results

`docs/benchmarks.md` has a measured run of two scenarios: urgent work behind a backlog (Priority
and Round Robin avoid the 13 late returns FCFS causes; too much aging reintroduces them) and
two sections submitting together (Round Robin fairness 1.000 vs 0.943; equal average wait).

## Suggested evaluation for the report

1. Use real data from one teacher (or a realistic synthetic queue): 3–4 courses, one large
   assignment, a few small ones, mixed priorities.
2. Record the comparison table for each policy: average wait, longest wait, missed grade-by
   dates, fairness. Vary the aging rate (0, 0.25, 1) and the quantum (1, 3, 10).
3. Ask teachers which order they would actually want, and why.
4. Future work: learn each teacher's real grading time per assignment by exponential
   averaging of measured times, `τ(n+1) = α·t(n) + (1−α)·τ(n)`, exactly as SJF predicts
   the next CPU burst.
