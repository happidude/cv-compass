#!/usr/bin/env bash
# Usage: check-flyway-immutable.sh BASE HEAD
# Fails when a migration present at the merge-base was modified/deleted/renamed, or when an added
# migration has an invalid name, a duplicate version, or a version not above the base tip maximum.
# Prints only "<CODE> <filename>" lines; never file contents.
set -euo pipefail

DIR="backend/src/main/resources/db/migration"
NAME_RE='^V([0-9]+([._][0-9]+)*)__.+\.sql$'

usage() { echo "USAGE check-flyway-immutable.sh BASE HEAD"; exit 2; }
[ "$#" -eq 2 ] || usage
BASE="$1"; HEAD_REF="$2"
git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null || usage
git rev-parse --verify --quiet "$HEAD_REF^{commit}" >/dev/null || usage
MB=$(git merge-base "$BASE" "$HEAD_REF") || usage

errors=0
fail() { echo "$1 $2"; errors=1; }

# Version -> comparable key: components split on . and _, leading zeros stripped, trailing zero
# components dropped (so V1 == V1_0), each component prefixed by its length so plain string
# comparison orders numerically for any length (timestamps included).
vkey() {
  local IFS='._' part out="" comps=() n
  for part in $1; do
    part="${part#"${part%%[!0]*}"}"; comps+=("${part:-0}")
  done
  n=${#comps[@]}
  while [ "$n" -gt 1 ] && [ "${comps[$((n - 1))]}" = 0 ]; do n=$((n - 1)); done
  for ((i = 0; i < n; i++)); do out+=$(printf '%03d%s.' "${#comps[i]}" "${comps[i]}"); done
  printf '%s' "$out"
}
version_of() { local n="${1##*/}"; [[ "$n" =~ $NAME_RE ]] && printf '%s' "${BASH_REMATCH[1]}"; return 0; }
is_repeatable() { [[ "${1##*/}" =~ ^R__.+\.sql$ ]]; }

# Capture git output first so a git failure aborts (set -e) instead of silently passing.
changes=$(git diff --name-status --no-renames "$MB" "$HEAD_REF" -- "$DIR")
base_files=$(git ls-tree -r --name-only "$BASE" -- "$DIR/")
head_files=$(git ls-tree -r --name-only "$HEAD_REF" -- "$DIR/")

# 1) existing files must not change (repeatable R__ files may be edited by design, not deleted)
while IFS=$'	' read -r status path; do
  [ -n "${status:-}" ] || continue
  case "$status" in
    M|T) is_repeatable "$path" && [ "$status" = M ] || fail MODIFIED "${path##*/}" ;;
    D) fail DELETED "${path##*/}" ;;
  esac
done <<< "$changes"

# 2) max version at base tip; does base already use repeatable migrations?
max_key=""; base_has_repeatable=false
while IFS= read -r f; do
  [ -n "$f" ] || continue
  if is_repeatable "$f"; then base_has_repeatable=true; continue; fi
  v=$(version_of "$f")
  [ -n "$v" ] || continue
  k=$(vkey "$v")
  if [[ -z "$max_key" || "$k" > "$max_key" ]]; then max_key="$k"; fi
done <<< "$base_files"

# 3) added files
while IFS=$'	' read -r status path; do
  [ "${status:-}" = A ] || continue
  f="${path##*/}"
  if is_repeatable "$f"; then
    [ "$base_has_repeatable" = true ] || fail INVALID_NAME "$f"
    continue
  fi
  v=$(version_of "$f")
  if [ -z "$v" ]; then fail INVALID_NAME "$f"; continue; fi
  k=$(vkey "$v")
  if [[ -n "$max_key" && ! "$k" > "$max_key" ]]; then fail VERSION_NOT_INCREASING "$f"; fi
done <<< "$changes"

# 4) duplicate versions in head tree
declare -A seen=()
while IFS= read -r f; do
  [ -n "$f" ] || continue
  v=$(version_of "$f")
  [ -n "$v" ] || continue
  k=$(vkey "$v")
  if [ -n "${seen[$k]:-}" ]; then fail DUPLICATE_VERSION "${f##*/}"; fi
  seen[$k]=1
done <<< "$head_files"

if [ "$errors" -ne 0 ]; then exit 1; fi
echo "OK flyway-immutable"
