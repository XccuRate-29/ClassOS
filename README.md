# ClassOS — Smart Classroom Platform

Role-based platform for timetabling, conflict detection, secure attendance and an
OS-scheduling-based assignment evaluation queue.

| Layer | Technology | Folder |
|-------|------------|--------|
| UI | React 18 + Vite + Tailwind CSS 4 | `frontend/` |
| Backend API | Java 21, Spring Boot 3.3, Spring Security (JWT) | `backend/` |
| OS-concept core | C++17: timetable solver, grading-queue scheduler (FCFS / Priority with aging / Round Robin), Banker's algorithm | `core/` |
| Database | PostgreSQL 16, Flyway migrations | `backend/src/main/resources/db/migration/` |
| File storage | Local disk, files named by UUID, served only through access-checked API calls | `STORAGE_DIR` (default `backend/data/files`) |

## Status

- [x] Milestone 0: repository skeleton, environment record, Docker database
- [x] Milestone 1: V1 schema with constraints, JWT authentication, role-based access control
- [x] C++ core: Banker's algorithm with thread-safe allocator and tests
- [x] Frontend: login, session restore, role-guarded routes, admin user management
- [x] Milestone 2: C++ timetable solver (CSP + MRV/DSatur), conflict detection API, concurrency-safe room bookings, admin timetable editor, personal timetables
- [x] Admin setup: terms and period grid, rooms, departments, courses, sections and enrolment, offerings, teacher availability; CSV user import; deactivate users; change password
- [x] Milestone 3: rotating QR/OTP attendance with device binding, attempt limits, optional campus-network check, burst-safe marking, background auto-close
- [x] Milestone 4: classroom workspace (materials, assignments, submissions, grading with optimistic locking, protected file storage) and evaluation queue (FCFS / Priority with aging / Round Robin in C++, policy comparison)
- [x] Milestone 5 tooling: full-stack Docker profile, health endpoint, CI, smoke test, k6 load tests with correctness thresholds, in-app feedback and SUS survey, login rate limiting, pool and server tuning
- [ ] Milestone 5 results: run the tests and the user trial, fill in `docs/results-template.md` (see `docs/evaluation-plan.md`)

## Full stack in one command

```bash
docker compose --profile full up -d --build     # database, C++ service, backend, frontend
bash scripts/smoke.sh                            # every line should say PASS
```

Open http://localhost:8088, or http://<this machine's IP>:8088 from phones on the same network.

## First-time setup

1. Copy `.env.example` to `.env` and fill in every value (see comments in the file).
2. Generate the Maven wrapper once, so teammates share the same Maven version:
   ```bash
   cd backend
   mvn -N wrapper:wrapper
   ```
   Commit `mvnw`, `mvnw.cmd` and `.mvn/`.

## Run

```bash
# 1. Database and timetable solver
docker compose up -d --build
docker compose ps            # wait until db shows "healthy"

# 2. Backend (Flyway creates the schema on first start)
cd backend
./mvnw spring-boot:run       # Windows: mvnw.cmd spring-boot:run
```

The backend reads the repo-root `.env` automatically when started from `backend/`.

```bash
# 3. Frontend (in a second terminal)
cd frontend
npm install        # first time only; commit the generated package-lock.json
npm run dev        # open http://localhost:5173
```

To run the solver natively instead of in Docker:

```bash
cd core
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
./build/classos_solver_server        # listens on 127.0.0.1:7070
```

### Setting up a real term

Sign in as admin and open **Setup**, then work through the tabs in order: create the term
and its period grid (and make it current), add rooms, departments and courses, add sections,
import students on **People** (CSV) and enrol them on the Sections tab, assign courses and
teachers, record teacher unavailability. Then open **Timetable** and generate.

### Sample data

Set `DEV_SEED_ENABLED=true` and a `DEV_SEED_PASSWORD` in `.env`, then start the backend.
It creates a fictional CSE department: term "Odd 2026" (Mon–Fri, 8 periods, lunch after
period 4), 6 classrooms, a seminar hall, 3 labs, 8 teachers, a lab assistant, 5 sections
and 25 course offerings. All seeded accounts use `DEV_SEED_PASSWORD`, for example
`anita.rawat@classos.local` (teacher), `deepak.thapa@classos.local` (lab assistant),
`student.cse-3a-1@classos.local` (student). Then sign in as admin, open **Timetable**
and select **Generate timetable**.

**Attendance from phones:** run `npm run dev -- --host` and open ClassOS on the teacher's
laptop through the printed Network address, so QR codes point somewhere phones can reach.
See `docs/attendance.md`.

The Vite dev server forwards `/api/*` to `localhost:8080`, so no CORS setup is needed in
development. Sign in with the admin from `.env`, add a teacher and a student under
**People**, then sign in as each in a private window to see their dashboards.

### Try the API

```bash
# Log in as the bootstrap admin (values from your .env)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@classos.local","password":"<ADMIN_PASSWORD>"}'

# Use the accessToken from the response
curl -s http://localhost:8080/api/auth/me -H "Authorization: Bearer <token>"

# Create a teacher
curl -s -X POST http://localhost:8080/api/admin/users \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"email":"t1@classos.local","password":"teacher-pass-1","fullName":"T One","role":"TEACHER"}'
```

On Windows PowerShell, use `curl.exe` (not `curl`) and escape inner quotes, or use Postman/Bruno.

## Test

```bash
# Backend: needs Docker running (Testcontainers starts a throwaway PostgreSQL)
cd backend && ./mvnw test

# C++ core
cd core
cmake -S . -B build -G Ninja
cmake --build build
ctest --test-dir build --output-on-failure

# Optional: data-race detection (Linux/WSL with GCC or Clang)
cmake -S . -B build-tsan -G Ninja -DCLASSOS_TSAN=ON
cmake --build build-tsan && ctest --test-dir build-tsan
```

## API (Milestone 1)

| Method | Path | Access | Purpose |
|--------|------|--------|---------|
| POST | `/api/auth/login` | public | Returns a JWT and the user profile |
| GET | `/api/auth/me` | any signed-in user | Current profile (read from DB) |
| POST | `/api/admin/users` | ADMIN | Create a user with a role |
| GET | `/api/admin/users?page=0&size=20` | ADMIN | List users (paginated) |
| GET | `/api/teacher/home` | TEACHER, ADMIN | Placeholder for teacher dashboard |
| GET | `/api/lab/home` | LAB_ASSISTANT, ADMIN | Placeholder for lab dashboard |
| GET | `/api/student/home` | STUDENT | Placeholder for student dashboard |
| GET | `/api/timetable/me` | any signed-in user | Current term timetable for this user's role |
| GET | `/api/admin/timetable/setup` | ADMIN | Term, period grid, sections, rooms, offerings with hours scheduled |
| GET | `/api/admin/timetable?sectionId=` (or `teacherId`, `roomId`) | ADMIN | One timetable view |
| POST | `/api/admin/timetable/check` | ADMIN | Dry run: conflicts and warnings for a proposed slot |
| POST | `/api/admin/timetable/slots` | ADMIN | Add a class (409 conflicts, 422 warnings unless `force`) |
| PUT / DELETE | `/api/admin/timetable/slots/{id}` | ADMIN | Move / remove a class |
| PATCH | `/api/admin/timetable/slots/{id}/lock` | ADMIN | Pin or unpin (pinned classes survive regeneration) |
| POST | `/api/admin/timetable/generate` | ADMIN | Run the C++ solver and save the result |
| GET | `/api/admin/timetable/runs` | ADMIN | Recent generation runs with search statistics |
| GET | `/api/bookings/rooms`, `/api/bookings/room-day` | TEACHER, LAB_ASSISTANT, ADMIN | Rooms; what occupies a room on a date |
| POST / DELETE | `/api/bookings`, `/api/bookings/{id}` | TEACHER, LAB_ASSISTANT, ADMIN | Book (409 with clashes) / cancel own booking |
| POST | `/api/admin/users/import` | ADMIN | Create users from CSV (`email,full name,role`) with a temporary password |
| PATCH | `/api/admin/users/{id}/active` | ADMIN | Deactivate or reactivate (never yourself or the last admin) |
| POST | `/api/auth/password` | any signed-in user | Change own password (needs the current one) |
| POST | `/api/feedback` | any signed-in user | Send feedback (category, optional 1-5 rating, message, page) |
| GET / POST | `/api/feedback/sus` | any signed-in user | Own SUS status / submit the 10 answers (scored 0-100) |
| GET / PATCH | `/api/admin/feedback`, `/api/admin/feedback/{id}` | ADMIN | List and triage feedback |
| GET | `/api/admin/feedback/sus`, `/api/admin/feedback/export` | ADMIN | SUS summary; all feedback as CSV |
| GET | `/actuator/health` | public | Status of the backend, database and C++ service |
| GET / POST / PUT | `/api/admin/setup/departments`, `/terms`, `/rooms`, `/courses` | ADMIN | Reference data (rooms are deactivated, not deleted) |
| POST | `/api/admin/setup/terms/{id}/current` | ADMIN | Make this the current term |
| PUT | `/api/admin/setup/terms/{id}/periods` | ADMIN | Replace the term's period grid |
| GET / POST / PUT / DELETE | `/api/admin/setup/sections` (`?termId=`) | ADMIN | Sections of a term |
| GET / POST / DELETE | `/api/admin/setup/sections/{id}/students` | ADMIN | Enrol by email list (reports what happened to each email) |
| GET / POST / DELETE | `/api/admin/setup/offerings` (`?termId=`), `PUT …/{id}/teacher` | ADMIN | Who teaches what; changing a teacher re-checks their timetable |
| GET / POST / DELETE | `/api/admin/setup/unavailability` (`?termId=`) | ADMIN | Times a teacher can't teach |
| GET | `/api/workspace/courses` | signed in | This term's courses for the caller (teacher, student or admin) |
| GET | `/api/workspace/offerings/{id}` | course members | Materials and assignments (students see their own submission) |
| POST | `/api/workspace/offerings/{id}/materials` | course teacher | Share a file (multipart: `title`, `description`, `file`) |
| GET / DELETE | `/api/workspace/materials/{id}/file`, `/api/workspace/materials/{id}` | members / teacher | Download / remove material |
| POST | `/api/workspace/offerings/{id}/assignments` | course teacher | Multipart: JSON part `data` + optional `file` |
| GET / PUT / DELETE | `/api/workspace/assignments/{id}` | members / teacher | Detail (teacher sees all submissions) / edit / delete if nothing submitted |
| POST | `/api/workspace/assignments/{id}/submission` | enrolled student | Submit or resubmit (multipart `file`) |
| GET | `/api/workspace/submissions/{id}/file` | owner, teacher, admin | Download a submission |
| POST | `/api/workspace/submissions/{id}/grade` | course teacher | `{marks, feedback, version}`; 409 if the student resubmitted |
| GET | `/api/teacher/grading/queue?policy=&agingPerHour=&quantum=` | TEACHER | Ordered queue with planned times and metrics |
| GET | `/api/teacher/grading/compare` | TEACHER | All three policies' metrics on the current queue |
| GET | `/api/teacher/attendance/today` | TEACHER | Today's classes, own offerings, open session |
| POST | `/api/teacher/attendance/sessions` | TEACHER | Open attendance for a class |
| GET | `/api/teacher/attendance/sessions/{id}/code` | TEACHER (owner) | Current rotating code, time to next code, live count |
| GET | `/api/teacher/attendance/sessions/{id}` | TEACHER (owner) | Students with status, rejected-attempt summary |
| POST | `/api/teacher/attendance/sessions/{id}/manual` | TEACHER (owner) | Mark a student present or absent by hand |
| POST | `/api/teacher/attendance/sessions/{id}/close` | TEACHER (owner) | Close; everyone unmarked becomes absent |
| GET | `/api/teacher/attendance/history` | TEACHER | Last 50 sessions with counts |
| POST | `/api/student/attendance/mark` | STUDENT | Mark present (needs `X-Device-Id`); status code per outcome |
| GET | `/api/student/attendance/open`, `/summary`, `/device` | STUDENT | Open sessions, percentage per course, phone status |
| DELETE | `/api/admin/attendance/devices/{studentId}` | ADMIN | Let a student register a new phone |

Errors use RFC 7807 JSON (`status`, `detail`, and `fields` for validation errors).
Status codes: 400 invalid input, 401 not signed in / bad token, 403 wrong role, 409 conflict, 429 too many failed sign-ins,
422 warnings need confirmation, 503 solver not reachable.

## Known limitations (to address or document)

- **Token revocation:** JWTs are stateless, so a deactivated user's existing token stays
  valid until it expires (default 60 min). Options: short TTL + refresh tokens, or a
  per-user token version checked on each request.
- **Token stored in `sessionStorage`:** survives a refresh, cleared when the tab closes,
  but readable by any script on the page if an XSS bug exists. A stronger design is an
  HttpOnly cookie set by the backend (then CSRF protection must be turned back on).
- **Login rate limits are per backend instance** and reset on restart; with several
  instances they would need a shared store (e.g. Redis). Anyone can lock an account for
  15 minutes by failing on purpose (temporary, by design).
- **One role per user:** see `docs/schema.md`.
- **Uploaded files live on the backend's disk** (`STORAGE_DIR`). Back that folder up
  together with the database; one without the other is incomplete.
- **No virus scanning of uploads.** Files are only ever downloaded, never executed or shown
  inline, and dangerous types are refused, but a real deployment should add scanning.
- **Device binding can be bypassed by a determined student** who copies the browser's stored
  device id to another phone. It raises the effort of proxy marking; see the threat model in
  `docs/attendance.md`.
- **Labs are scheduled for the whole section.** Real labs often split a section into
  batches; that needs a batch entity and is not modelled yet.
- **Generation holds locks while solving** (see `docs/concurrency.md`).
- **Banker's allocator is in-memory:** it decides; the database records what is allocated.
  After a restart, the allocator must be rebuilt from the database.

## Milestone 2 design notes

- **Timetabling is graph colouring, not shortest paths.** Classes sharing a teacher or a
  section are adjacent in a conflict graph; time slots are colours. Dijkstra's algorithm
  solves a different problem and is not used.
- **The solver proposes, the database decides.** Every generated slot is inserted under the
  same exclusion constraints as manual edits, so a solver bug cannot create a clash.
- **Suggested evaluation for your report:** compare MRV/DSatur ordering against plain
  "first unplaced event" ordering on the same instances (events placed, nodes, time), and
  measure how many conflicts manual entry produces without the live check.

Further reading: `docs/evaluation-plan.md` (Milestone 5), `docs/benchmarks.md` (algorithm measurements), `loadtest/README.md`, `docs/evaluation-queue.md` (grading scheduler), `docs/attendance.md` (attendance threat model), `docs/schema.md` (ER diagram, constraints), `docs/concurrency.md`
(locking and deadlock prevention), `docs/solver-protocol.md` (C++ service API).

## Frontend structure

| Path | Responsibility |
|------|----------------|
| `src/api/client.js` | One `fetch` wrapper: adds the token, turns server errors (RFC 7807) into `ApiError`, signs out on an expired token |
| `src/auth/AuthContext.jsx` | Current user, `login`, `logout`, restores the session on page load via `/api/auth/me` |
| `src/auth/RequireRole.jsx` | Route guard. Convenience only: the backend enforces every permission independently |
| `src/pages/admin/` | People page: add users, paginated user list |
| `src/pages/RoleHome.jsx` | Placeholder dashboards for Teacher, Student, Lab assistant |
