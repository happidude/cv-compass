#!/usr/bin/env bash
# Tests for scripts/ci/check-flyway-immutable.sh using throwaway git repos.
set -uo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/check-flyway-immutable.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
DIR=backend/src/main/resources/db/migration
MARKER="SECRET_SQL_MARKER_9f3a"
fails=0

g() { git -C "$REPO" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false -c core.autocrlf=false -c core.safecrlf=false "$@"; }
sql() { printf 'SELECT 1; -- %s\n' "$MARKER" > "$REPO/$DIR/$1"; }
commit() { g add -A >/dev/null; g commit -q -m "$1"; }

new_repo() {
  REPO="$TMP/$1"; mkdir -p "$REPO/$DIR"; git init -q -b main "$REPO"
  sql V1__init.sql; sql V9__nine.sql; sql V12__twelve.sql
  commit base; BASE=$(g rev-parse HEAD)
  g checkout -q -b pr
}

# run_case NAME EXPECT_EXIT EXPECT_OUTPUT_REGEX
run_case() {
  local name="$1" want_rc="$2" want_re="$3" out rc
  out=$(cd "$REPO" && bash "$SCRIPT" "$BASE" "$(g rev-parse HEAD)" 2>&1); rc=$?
  if [ "$rc" -eq "$want_rc" ] && printf '%s' "$out" | grep -qE "$want_re" && ! printf '%s' "$out" | grep -q "$MARKER"; then
    echo "PASS $name"
  else
    echo "FAIL $name (rc=$rc)"; fails=1
  fi
}

new_repo c1; g commit -q --allow-empty -m noop; run_case "no change OK" 0 '^OK flyway-immutable'
new_repo c2; sql V13__new.sql; commit add; run_case "new V13 OK" 0 '^OK'
new_repo c3; echo "-- changed $MARKER" >> "$REPO/$DIR/V9__nine.sql"; commit mod; run_case "modify existing" 1 '^MODIFIED V9__nine.sql'
new_repo c4; rm "$REPO/$DIR/V9__nine.sql"; commit del; run_case "delete existing" 1 '^DELETED V9__nine.sql'
new_repo c5; g mv "$DIR/V9__nine.sql" "$DIR/V20__nine.sql"; commit ren; run_case "rename existing" 1 '^DELETED V9__nine.sql'
new_repo c6; sql V5__low.sql; commit low; run_case "new V5 <= max" 1 '^VERSION_NOT_INCREASING V5__low.sql'
new_repo c7; sql V13__a.sql; sql V13__b.sql; commit dup; run_case "duplicate version" 1 '^DUPLICATE_VERSION'
new_repo c8; sql V10__ten.sql; commit v10; run_case "V10 vs V9 numeric (V10 < V12 still fails)" 1 '^VERSION_NOT_INCREASING V10__ten.sql'
new_repo c9; sql V12_1__sub.sql; commit sub; run_case "V12_1 > V12 OK" 0 '^OK'
new_repo c10; sql bad_name.sql; commit bad; run_case "invalid name" 1 '^INVALID_NAME bad_name.sql'
# base tip newer than merge-base: develop gained V14, PR adds V13
new_repo c11; g checkout -q main; sql V14__dev.sql; commit dev; BASE=$(g rev-parse HEAD)
g checkout -q pr; sql V13__pr.sql; commit prv; run_case "base tip newer than merge-base" 1 '^VERSION_NOT_INCREASING V13__pr.sql'
new_repo c12; mkdir -p "$REPO/$DIR/sub"; printf 'SELECT 1; -- %s
' "$MARKER" > "$REPO/$DIR/sub/V13__sub.sql"; commit subdir; run_case "subdir migration OK" 0 '^OK'
new_repo c13; sql V13__a.sql; sql V13_0__b.sql; commit dup0; run_case "V13 vs V13_0 duplicate" 1 '^DUPLICATE_VERSION'
new_repo c14; blob=$(printf 'V1__init.sql' | g hash-object -w --stdin)
g update-index --cacheinfo "120000,$blob,$DIR/V9__nine.sql"; g commit -q -m typ; run_case "type change" 1 '^MODIFIED V9__nine.sql'
new_repo c15; sql V20260101120000__ts.sql; commit ts; run_case "timestamp version OK" 0 '^OK'
new_repo c16; sql R__views.sql; commit rep; run_case "R__ without base R__ invalid" 1 '^INVALID_NAME R__views.sql'
new_repo c17; sql R__views.sql; commit r0; BASE=$(g rev-parse HEAD); echo "-- e" >> "$REPO/$DIR/R__views.sql"; sql R__more.sql; commit r1
run_case "R__ edit and add allowed" 0 '^OK'
# git failure path: outside any repository the script must exit non-zero
out=$(cd "$TMP" && GIT_CEILING_DIRECTORIES="$TMP" bash "$SCRIPT" HEAD HEAD 2>&1); rc=$?
if [ "$rc" -ne 0 ]; then echo "PASS git failure non-zero"; else echo "FAIL git failure non-zero"; fails=1; fi
# usage
REPO="$TMP/c1"; out=$(cd "$REPO" && bash "$SCRIPT" onlyone 2>&1); rc=$?
if [ "$rc" -eq 2 ] && printf '%s' "$out" | grep -q '^USAGE'; then echo "PASS usage"; else echo "FAIL usage"; fails=1; fi

exit "$fails"
