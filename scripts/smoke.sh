#!/usr/bin/env bash
# Integration smoke test: is every part of the running stack wired up correctly?
# Usage (from the repo root, after starting the stack):
#   bash scripts/smoke.sh                       # full stack at http://localhost:8088
#   BASE_URL=http://localhost:5173 bash scripts/smoke.sh   # dev servers
# Reads ADMIN_EMAIL, ADMIN_PASSWORD and (optional) DEV_SEED_PASSWORD from .env.
set -u
BASE_URL="${BASE_URL:-http://localhost:8088}"
[ -f .env ] && set -a && . ./.env && set +a

pass=0; fail=0
ok()   { echo "PASS  $1"; pass=$((pass + 1)); }
bad()  { echo "FAIL  $1"; fail=$((fail + 1)); }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
token_for() {
  curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
       -d "{\"email\":\"$1\",\"password\":\"$2\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

[ "$(code "$BASE_URL/")" = "200" ] && ok "frontend serves the app" || bad "frontend serves the app"
health=$(curl -s "$BASE_URL/actuator/health")
echo "$health" | grep -q '"status":"UP"' && ok "backend healthy: $health" || bad "backend healthy: $health"
echo "$health" | grep -q '"solver":{"status":"UP"' && ok "C++ solver reachable" || bad "C++ solver reachable (generation and scheduling need it)"

admin=$(token_for "$ADMIN_EMAIL" "$ADMIN_PASSWORD")
[ -n "$admin" ] && ok "admin can sign in" || bad "admin can sign in (check ADMIN_EMAIL / ADMIN_PASSWORD)"
AUTH=(-H "Authorization: Bearer $admin")
[ "$(code "${AUTH[@]}" "$BASE_URL/api/auth/me")" = "200" ] && ok "token accepted" || bad "token accepted"
[ "$(code "$BASE_URL/api/admin/users")" = "401" ] && ok "no token -> 401" || bad "no token -> 401"
[ "$(code "${AUTH[@]}" "$BASE_URL/api/admin/setup/terms")" = "200" ] && ok "setup API" || bad "setup API"

if [ -n "${DEV_SEED_PASSWORD:-}" ]; then
  teacher=$(token_for "anita.rawat@classos.local" "$DEV_SEED_PASSWORD")
  student=$(token_for "student.cse-3a-1@classos.local" "$DEV_SEED_PASSWORD")
  if [ -n "$teacher" ] && [ -n "$student" ]; then
    ok "seeded teacher and student can sign in"
    [ "$(code -H "Authorization: Bearer $student" "$BASE_URL/api/admin/users")" = "403" ] && ok "student blocked from admin API (403)" || bad "student blocked from admin API"
    [ "$(code -H "Authorization: Bearer $student" "$BASE_URL/api/timetable/me")" = "200" ] && ok "student timetable" || bad "student timetable"
    [ "$(code -H "Authorization: Bearer $teacher" "$BASE_URL/api/teacher/attendance/today")" = "200" ] && ok "teacher attendance" || bad "teacher attendance"
    [ "$(code -H "Authorization: Bearer $teacher" "$BASE_URL/api/teacher/grading/queue?policy=ROUND_ROBIN")" = "200" ] && ok "grading queue" || bad "grading queue"
    [ "$(code -H "Authorization: Bearer $student" "$BASE_URL/api/workspace/courses")" = "200" ] && ok "classroom workspace" || bad "classroom workspace"
  else
    bad "seeded accounts sign in (is DEV_SEED_ENABLED=true?)"
  fi
else
  echo "SKIP  role checks (set DEV_SEED_PASSWORD to run them)"
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
