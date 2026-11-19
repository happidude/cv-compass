#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "${BASH_SOURCE[0]%/*}/.." && pwd)
run_dir="$repo/.dev/run"
services='backend,frontend'
stop_database=false dry_run=false failed=false
usage() { echo 'Usage: scripts/dev-down.sh [--service backend,frontend] [--stop-database] [--dry-run]'; }
while (($#)); do
    case "$1" in
        --service) [[ $# -ge 2 && -n $2 ]] || { usage; exit 2; }; services=$2; shift 2;;
        --stop-database) stop_database=true; shift;;
        --dry-run) dry_run=true; shift;;
        -h|--help) usage; exit 0;;
        *) usage; exit 2;;
    esac
done
IFS=, read -ra selected <<< "$services"
for svc in "${selected[@]}"; do
    case "$svc" in backend|frontend) ;; *) usage; exit 2;; esac
done
proc_start() {
    local stat rest
    [[ -r /proc/$1/stat ]] || return 1
    stat=$(<"/proc/$1/stat"); rest=${stat##*) }
    read -ra fields <<< "$rest"
    printf '%s' "${fields[19]}"
}
proc_cmd() { tr '\0' ' ' < "/proc/$1/cmdline"; }
stop_service() {
    local svc=$1 file="$run_dir/$1.rec" pid start cmd current signal count i
    if [[ ! -f $file ]]; then echo "$svc: nothing to stop"; return; fi
    if $dry_run; then
        echo "DRY RUN $svc: verify /proc/<pid>/stat field 22 and cmdline against .dev/run/$svc.rec"
        echo '  If matched: kill -INT -- -<pid>; wait <=30s; TERM; wait <=10s; KILL; remove record'
        return
    fi
    if ! { IFS= read -r pid; IFS= read -r start; IFS= read -r cmd; } < "$file"; then
        echo "SKIP $svc: invalid record"; rm -- "$file"; return
    fi
    if [[ ! $pid =~ ^[0-9]+$ || $pid -le 1 || ! -d /proc/$pid ]]; then
        echo "STALE $svc: process is no longer running"; rm -- "$file"; return
    fi
    current=$(proc_start "$pid") || current=''
    if [[ $current != "$start" || $(proc_cmd "$pid") != "$cmd" ]]; then
        echo "SKIP $svc: pid $pid does not match record"; rm -- "$file"; return
    fi
    for signal in INT TERM KILL; do
        # Recheck identity before every signal in case the PID was reused during the wait.
        [[ $(proc_start "$pid" || :) == "$start" ]] || break
        if [[ $(proc_cmd "$pid") != "$cmd" ]]; then
            echo "SKIP $svc: pid $pid does not match record"; rm -- "$file"; return
        fi
        kill -"$signal" -- "-$pid" 2>/dev/null || :
        count=30; [[ $signal != TERM ]] || count=10; [[ $signal != KILL ]] || count=1
        for ((i=0; i<count; i++)); do
            [[ $(proc_start "$pid" || :) == "$start" && -n $(proc_cmd "$pid" 2>/dev/null || :) ]] || break
            sleep 1
        done
    done
    if [[ $(proc_start "$pid" || :) == "$start" && -n $(proc_cmd "$pid" 2>/dev/null || :) ]]; then
        echo "SKIP $svc: process is still running; record retained"; failed=true
    else
        rm -- "$file"; echo "STOPPED $svc"
    fi
}
for file in "$run_dir/ocr-worker.json" "$run_dir/llm-worker.json" "$run_dir/"*-worker.rec "${XDG_STATE_HOME:-$HOME/.local/state}/cv-compass-dev/run/"win-*.rec; do
    [[ -f $file ]] || continue
    svc=${file##*/}; svc=${svc%.*}; svc=${svc#win-}
    echo "WARNING $svc: legacy worker record kept; dừng thủ công bằng Ctrl+C trong cửa sổ worker ($file)"
done
for svc in frontend backend; do
    for chosen in "${selected[@]}"; do
        if [[ $chosen == "$svc" ]]; then stop_service "$svc"; break; fi
    done
done
if $stop_database; then
    if [[ ! -f $run_dir/database.json ]]; then
        echo 'Database: nothing to stop (no mode record)'
    else
        mode=$(<"$run_dir/database.json")
        case "$mode" in
            '{"mode":"dev"}') container=cv-compass-postgres;;
            '{"mode":"test"}') container=cv-compass-postgres-test;;
            '{"mode":"none"}') container='';;
            *) echo 'SKIP database: invalid mode record'; container=''; failed=true;;
        esac
        if [[ -n $container ]]; then
            echo "docker stop $container"
            if ! $dry_run; then docker stop "$container" || failed=true; fi
        fi
    fi
else
    echo 'Database kept running (use --stop-database to stop the recorded container).'
fi
$failed && exit 1
exit 0
