# Milestone 5: integration, evaluation and refinement plan

Milestone 5 turns "the code is written" into "we know how well it works". The tooling is
in the repository; the results come from running it. Record every number in
`docs/results-template.md`. Never report a number you did not measure.

## Step 1: Integration (first week)

1. **Make the build green.** Push to GitHub; the CI workflow (`.github/workflows/ci.yml`)
   runs the C++, Java and frontend test suites. Fix failures until all three jobs pass.
   Turn on "require status checks to pass" for the main branch.
2. **Run the whole stack:** `docker compose --profile full up -d --build`, then open
   `http://localhost:8088`.
3. **Smoke test:** `bash scripts/smoke.sh`. Every line must say PASS.
4. **Walk through every role by hand** with the sample data (checklist below). Log each
   problem as a GitHub issue.

| Role | Check |
|------|-------|
| Admin | Create a term and period grid, rooms, a course, a section; import students by CSV; enrol them; assign a teacher; generate the timetable; add and move a class; see a clash refused |
| Teacher | See today's classes; open attendance; show the QR full screen; post an assignment; grade from the queue under each policy; book a room |
| Student | See the week; mark attendance by scanning with a real phone; submit and resubmit an assignment; see the grade; change password |
| Lab assistant | See lab timetables; book a lab |

## Step 2: Load testing

See `loadtest/README.md`. Run each script three times and report the median run.

| Test | Question it answers | Target |
|------|---------------------|--------|
| `attendance-burst.js` (60, then 120 students) | Can a whole class mark at once without errors or lost records? | all accepted; p95 < 500 ms; exactly N present |
| `booking-contention.js` (40 teachers) | Does the row lock guarantee exactly one booking under contention? | exactly 1 created; rest 409; p95 < 1 s |
| `read-mix.js` (100 users) | Is normal daytime use fast? | < 1% errors; p95 < 300 ms |

Also measure, from the admin screen: timetable generation time and classes placed for the
sample department and for one larger realistic department.

## Step 3: Performance refinement

For every failed threshold or slow endpoint: find the cause (k6 tag, backend log,
`EXPLAIN ANALYZE`), change one thing, re-run the same test, record before and after.

Changes already made from code review, **not yet measured**, so measure them rather than
claiming them:

| Change | Expected effect | Measure with |
|--------|-----------------|--------------|
| Connection pool 10 to 20 (`DB_POOL_SIZE`) | Less queueing during the attendance burst | attendance-burst at 120, pool 10 vs 20 |
| Course card fetched by id instead of loading all courses | Faster course and assignment pages | read-mix (`courses`), or timing one page |
| JSON response compression | Smaller responses on slow networks | response size in browser dev tools |
| Login failure limits | Blocks password guessing; no effect on correct sign-ins | try 6 wrong passwords; check 429 |

## Step 4: User trial and feedback

**Before the trial.** Collect the baseline (process Step 2): time to take roll call for one
class of 60, clashes per semester in the current timetable, how room bookings work today.

**The trial (one to two weeks).** One or two sections, their teachers, and a lab assistant
use ClassOS for real: timetable, attendance, one assignment.

**Consent and data protection.**
- Ask your guide whether departmental approval is needed for a trial with students.
- Tell participants what is recorded (attendance, submissions, feedback), who can see it,
  and that taking part does not affect grades.
- Use the trial's attendance only as a parallel record; keep the official roll call during the trial.
- Report results only as totals and averages, never by name. Delete trial data afterwards,
  or as agreed with the department.

**Feedback collected.**
- **In-app Feedback button** (every page): problems and ideas, with the page they came from.
  Export: Feedback results, Export all as CSV.
- **System Usability Scale** at `/survey`, after at least one week of use. Ask everyone in
  the trial. Results appear on the Feedback results page.
- **Short interviews** (15 minutes, at least 2 teachers and 4 students): what saved time,
  what was confusing, what they would change, and would they keep using it.

## Step 5: Refine and report

1. Group the feedback into themes; fix the top three problems; describe each fix in the report.
2. Compare the trial with the baseline: time to take attendance, clashes, booking conflicts.
3. Fill in `docs/results-template.md` and write the results and discussion chapter from it,
   including what did not work and what you would do next.
