# Secure attendance (Milestone 3)

## How it works

1. The teacher opens attendance for a class. The server creates a `class_sessions` row with a
   random 16-byte nonce and an automatic close time (default 15 minutes).
2. The teacher's screen shows a QR code and the same 6-digit code. Both change every
   `ATTENDANCE_WINDOW_SECONDS` (default 10). The QR encodes a link: `/attend?s=<session>&c=<code>`.
3. A student scans it with the phone camera (or types the code on the Attendance page).
   The browser sends the code plus its device id (`X-Device-Id` header).
4. The server checks, in order: device id present, campus network (if configured), attempt
   limit, session open, student enrolled, code valid, device binding. Then it inserts the record.
5. When the teacher closes the session (or the background sweeper closes it at the time
   limit), every enrolled student without a record is recorded ABSENT in the same transaction.

Codes are `HMAC-SHA256(server key, session id ‖ nonce ‖ window)`, truncated to 6 digits as in
TOTP (RFC 6238). Nothing is stored per rotation. The current and previous windows are
accepted, so a code is usable for 10–20 seconds.

## Threat model

| Attack | Defence | Enforced by | What still gets through |
|--------|---------|-------------|-------------------------|
| Photo of the code sent to an absent friend | Code expires in 10–20 s; late codes logged as `EXPIRED_CODE` | Server (HMAC window check) | A live video call relaying the screen in real time |
| One phone marks several friends | One device per student, one student per device; one device per session | **Database** (`uq_device_one_student`, `uq_one_device_per_session`) | A friend physically carrying several phones |
| Friend uses their own phone for you | Your account is bound to your phone (`DEVICE_MISMATCH`) | Server + database | Copying the browser's stored device id to another phone (raises effort, not impossible) |
| Guessing the 6-digit code | 5 wrong or expired attempts per session, then blocked | Server (attempt log) | Nothing practical: 5 guesses in 10^6 |
| Marking from home over mobile data | Optional campus IP range (`ATTENDANCE_ALLOWED_CIDRS`) | Server | A VPN into the campus network, if one exists |
| Marking for another section's class | Enrolment check | Server | — |
| Forged codes after a database leak | Server key is never in the database | Design | A leak of the server's environment variables |
| Double-tap or retry creates duplicates | `PRIMARY KEY (session_id, student_id)` + `ON CONFLICT DO NOTHING` | **Database** | — |

No technical scheme stops all proxy attendance. The teacher's live count ("23 of 60 present")
against a quick headcount, plus the rejected-attempt summary, are the final check.

## Concurrency

| Situation | What happens | Why it is safe |
|-----------|--------------|----------------|
| 60 students scan within seconds | All marks run in parallel | Marks only read the session row (`FOR SHARE`); inserts are idempotent; codes are computed, not looked up |
| Teacher closes during a burst | Close waits for marks in progress, then runs alone | Close takes the session row `FOR UPDATE` (readers–writers); later marks see `CLOSED` |
| Teacher forgets to close | Background sweeper closes it at the time limit | `@Scheduled` job; `FOR UPDATE SKIP LOCKED` skips sessions a teacher is closing |
| Two sessions opened at once | Second is rejected | Partial unique indexes: one open session per offering and per teacher |

Tests: `AttendanceConcurrencyTest` (60 simultaneous marks; close racing 40 marks, checking
every student ends with exactly one consistent record) and `AttendanceIntegrationTest`.

## Using it from phones during development

Phones must reach the frontend over the network, and the QR link uses the address the
teacher's browser is on:

1. Run the frontend with `npm run dev -- --host` and note the "Network" address
   (for example `http://192.168.1.20:5173`).
2. Open ClassOS on the teacher's laptop **through that address**, not `localhost`,
   so the QR links point somewhere phones can reach.
3. Students sign in on their phones before class (a code can expire while they log in).

In development, all requests reach Spring Boot through the Vite proxy, so the server sees
`127.0.0.1` as every client's IP; leave `ATTENDANCE_ALLOWED_CIDRS` empty until deployed
behind a correctly configured reverse proxy.

## Evaluation ideas for the report

- Proxy attempts caught: count `EXPIRED_CODE`, `DEVICE_IN_USE`, `DEVICE_MISMATCH` in
  `attendance_attempts` during a trial week.
- Time to take attendance: ClassOS vs calling the roll, for one class of 60.
- Burst latency: p95 time per mark with 60–120 simultaneous students (k6, Milestone 5).
