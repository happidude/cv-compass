#!/usr/bin/env bash
set -euo pipefail

llm_token=${CVC_LLM_WORKER_TOKEN-}
unset CVC_LLM_WORKER_TOKEN
repo=$(cd -- "${BASH_SOURCE[0]%/*}/.." && pwd)
run_dir="$repo/.dev/run"
log_dir="$repo/.dev/log"
state_dir="${XDG_STATE_HOME:-$HOME/.local/state}/cv-compass-dev/run"
database='' dry_run=false skip_build=false skip_backend=false skip_frontend=false skip_ocr=false with_llm=false
refused=false failed=false backend_ready=false
backend_port=${CVC_DEV_BACKEND_PORT:-8080}
frontend_port=${CVC_DEV_FRONTEND_PORT:-5173}
wait_seconds=${CVC_DEV_BACKEND_WAIT_SECONDS:-240}
python=${CVC_DEV_PYTHON:-python3.14}
expand_home() { case "$1" in '~/'*) printf '%s/%s' "$HOME" "${1:2}";; *) printf '%s' "$1";; esac; }
ocr_values=$(expand_home "${CVC_DEV_OCR_VALUES_FILE:-~/cv-ocr-private/values.txt}")
ocr_token=$(expand_home "${CVC_DEV_OCR_TOKEN_FILE:-~/cv-ocr-private/worker.token}")
ocr_config=$(expand_home "${CVC_DEV_OCR_PULL_CONFIG:-~/cv-ocr-private/pull.toml}")
usage() {
    cat <<'HELP'
Usage: scripts/dev-up.sh --database dev|test|none [options]
  --dry-run           Print commands without running checks or creating files
  --skip-build        Reuse the backend jar
  --skip-backend      Do not start or build the backend
  --skip-frontend     Do not start the frontend
  --skip-ocr-worker   Do not start the OCR worker
  --with-llm-worker   Run check, then start the optional LLM worker
  -h, --help          Show help
HELP
}
while (($#)); do
    case "$1" in
        --database) [[ $# -ge 2 ]] || { usage; exit 2; }; database=$2; shift 2;;
        --dry-run) dry_run=true; shift;;
        --skip-build) skip_build=true; shift;;
        --skip-backend) skip_backend=true; shift;;
        --skip-frontend) skip_frontend=true; shift;;
        --skip-ocr-worker) skip_ocr=true; shift;;
        --with-llm-worker) with_llm=true; shift;;
        -h|--help) usage; exit 0;;
        *) usage; exit 2;;
    esac
done
case "$database" in dev|test|none) ;; *) usage; exit 2;; esac
for number in "$backend_port" "$frontend_port" "$wait_seconds"; do
    [[ $number =~ ^[0-9]+$ && ${#number} -le 5 && $number -gt 0 && $number -le 65535 ]] || { echo 'Invalid port or wait duration'; exit 2; }
done
show() { printf '  '; printf '%q ' "$@"; printf '\n'; }
have() { command -v "$1" >/dev/null 2>&1; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }
proc_start() {
    local stat rest
    [[ -r /proc/$1/stat ]] || return 1
    stat=$(<"/proc/$1/stat"); rest=${stat##*) }
    # The command name may contain spaces or parentheses; field 22 is field 20 after it.
    read -ra fields <<< "$rest"
    printf '%s' "${fields[19]}"
}
proc_cmd() { tr '\0' ' ' < "/proc/$1/cmdline"; }
record_matches() {
    local file=$1 pid start cmd current
    [[ -f $file ]] || return 1
    { IFS= read -r pid; IFS= read -r start; IFS= read -r cmd; } < "$file" || return 1
    [[ $pid =~ ^[0-9]+$ && -d /proc/$pid ]] || return 1
    current=$(proc_start "$pid") || return 1
    [[ $current == "$start" && $(proc_cmd "$pid") == "$cmd" ]]
}
can_start() {
    local svc=$1 port=${2:-} file pid
    for file in "$run_dir/$svc.rec" "$state_dir/win-$svc.rec"; do
        [[ -f $file ]] || continue
        IFS= read -r pid < "$file" || pid=0
        if record_matches "$file"; then
            echo "REFUSED $svc: pid $pid is already running"; refused=true; return 1
        fi
        if [[ $pid =~ ^[0-9]+$ && -d /proc/$pid ]]; then
            echo "SKIP $svc: pid $pid does not match record"
        else
            echo "STALE $svc: process is no longer running"
        fi
        # A Windows-owned record is never removed by the Linux scripts.
        [[ $file != "$run_dir/"* ]] || rm -- "$file"
    done
    if [[ -n $port ]] && port_open "$port"; then
        echo "REFUSED $svc: port $port is already in use"; refused=true; return 1
    fi
    if [[ $svc == *-worker ]]; then
        local pattern='[r]un_pull_worker.py'
        [[ $svc != llm-worker ]] || pattern='[c]v_compass_llm pull'
        if ! have pgrep; then
            echo "SKIP $svc: pgrep is required to detect external workers"; failed=true; return 1
        fi
        if pgrep -f "$pattern" >/dev/null; then
            echo "WARNING $svc: an external worker is running; no additional worker started"
            return 1
        fi
    fi
}
launch() {
    local svc=$1 directory=$2; shift 2
    local log="$log_dir/$svc.log" pid start cmd
    echo "START $svc (log: .dev/log/$svc.log)"; show "$@"
    mkdir -p -- "$run_dir" "$log_dir" || { echo "SKIP $svc: cannot create runtime directories"; failed=true; return 1; }
    if [[ -f $log ]] && ! mv -f -- "$log" "$log_dir/$svc.prev.log"; then echo "SKIP $svc: cannot rotate log"; failed=true; return 1; fi
    (
        cd -- "$directory" || exit 1
        if [[ $svc == llm-worker ]]; then
            export CVC_LLM_WORKER_TOKEN="$llm_token"
            exec setsid "$@"
        else
            exec setsid env -u CVC_LLM_WORKER_TOKEN "$@"
        fi
    ) >"$log" 2>&1 < /dev/null &
    pid=$!
    sleep 1
    if ! start=$(proc_start "$pid") || ! cmd=$(proc_cmd "$pid") || [[ -z $cmd || $cmd == *$'\n'* ]]; then
        echo "SKIP $svc: process exited before recording; see log"; failed=true; return 1
    fi
    printf '%s\n%s\n%s\n' "$pid" "$start" "$cmd" > "$run_dir/$svc.rec" || { echo "SKIP $svc: cannot write process record"; failed=true; return 1; }
}
print_plan() {
    echo 'DRY RUN: no commands, probes, checks or file writes are performed.'
    echo "CVC_LLM_WORKER_TOKEN: $(if [[ -n $llm_token ]]; then echo set; else echo missing; fi)"
    case "$database" in
        dev) show env -u CVC_LLM_WORKER_TOKEN docker compose -f "$repo/infra/docker-compose.yml" up -d;;
        test) show env -u CVC_LLM_WORKER_TOKEN docker inspect cv-compass-postgres-test; show env -u CVC_LLM_WORKER_TOKEN docker start cv-compass-postgres-test; show env -u CVC_LLM_WORKER_TOKEN docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' cv-compass-postgres-test; echo '  Wait for container health (120 seconds maximum).';;
    esac
    if ! $skip_backend; then
        show env -u CVC_LLM_WORKER_TOKEN java -version
        echo '  Check DB_URL/DB_USERNAME/DB_PASSWORD presence and Test URL locally (values hidden).'
        $skip_build || show env -u CVC_LLM_WORKER_TOKEN ./gradlew bootJar --no-daemon
        echo "  In backend/: select one build/libs/*.jar excluding *-plain.jar"
        echo "  setsid env -u CVC_LLM_WORKER_TOKEN java '-Duser.timezone=UTC' -jar <jar> --cv.import.enabled=false > .dev/log/backend.log 2>&1 < /dev/null &"
        echo "  Wait for backend port $backend_port (up to $wait_seconds seconds)."
    fi
    if ! $skip_frontend; then
        show env -u CVC_LLM_WORKER_TOKEN node --version
        show env -u CVC_LLM_WORKER_TOKEN npm --version
        echo "  In frontend/: setsid env -u CVC_LLM_WORKER_TOKEN npm run dev -- --port $frontend_port --strictPort > .dev/log/frontend.log 2>&1 < /dev/null &"
    fi
    if ! $skip_ocr; then
        show env -u CVC_LLM_WORKER_TOKEN "$python" --version
        echo '  In repo/: setsid env -u CVC_LLM_WORKER_TOKEN bash -c <OCR script> > .dev/log/ocr-worker.log 2>&1 < /dev/null &'
        printf '%s\n' "$ocr_script"
    fi
    if $with_llm; then
        show env -u CVC_LLM_WORKER_TOKEN "$python" --version
        show env -u CVC_LLM_WORKER_TOKEN ollama --version
        echo '  In llm-worker/: PYTHONPATH=src (token inherited by check/pull only)'
        show "$python" -m cv_compass_llm check
        show setsid "$python" -m cv_compass_llm pull
        echo '  Redirect pull output to .dev/log/llm-worker.log (stdin /dev/null); record pid/start/cmd after exec.'
        [[ -n $llm_token ]] || echo '  Token missing: check/pull shown as a conditional plan; runtime would skip LLM.'
    else
        echo 'LLM worker is optional: set the session token and use --with-llm-worker; see docs/LOCAL_DEV.md section 2.'
    fi
    echo '  Rotate service logs to <svc>.prev.log; record pid/start/cmd in .dev/run/<svc>.rec.'
    echo '  TCP probes planned: 5432 5433 8080 5173 11434 and configured service ports.'
}
printf -v ocr_script 'set -euo pipefail\nsource <(grep -E "^(PY|BUILD|PROFILE)=" %q | sed "s/^/export /")\nexport CV_COMPASS_OCR_TOKEN="$(cat -- %q)"\nexec "$PY" worker/run_pull_worker.py --config %q' "$ocr_values" "$ocr_token" "$ocr_config"
if $dry_run; then print_plan; exit 0; fi

for port in 5432 5433 8080 5173 11434 "$backend_port" "$frontend_port"; do
    if port_open "$port"; then echo "PORT $port: listening"; else echo "PORT $port: free"; fi
done
if ! have setsid; then echo 'Missing setsid; no services started'; exit 1; fi
backend_ok=true frontend_ok=true ocr_ok=true llm_ok=true db_ok=true
if ! $skip_backend; then
    if ! have java || ! java_version=$(env -u CVC_LLM_WORKER_TOKEN java -version 2>&1) || [[ ! $java_version =~ version\ \"25([.\"]|$) ]]; then
        echo 'SKIP backend: Java 25 is required'; backend_ok=false; failed=true
    fi
    if [[ $database == test ]]; then
        if [[ -z ${DB_URL:-} || -z ${DB_USERNAME:-} || -z ${DB_PASSWORD:-} ]] || [[ ! ${DB_URL:-} =~ ^jdbc:postgresql://(127\.0\.0\.1|localhost):5433/cv_compass_test([?].*)?$ ]]; then
            echo 'SKIP backend: Test requires session DB_URL/DB_USERNAME/DB_PASSWORD and a loopback test URL on 5433'; backend_ok=false; failed=true
        fi
    elif [[ $database == dev && ( -n ${DB_URL:-} || -n ${DB_USERNAME:-} || -n ${DB_PASSWORD:-} ) ]]; then
        echo 'WARNING: session DB_* override backend/.env.properties'
    fi
fi
if ! $skip_frontend; then
    if ! have node || ! have npm || [[ ! -d $repo/frontend/node_modules ]]; then
        echo 'SKIP frontend: Node, npm and frontend/node_modules required; run npm ci in frontend/'; frontend_ok=false; failed=true
    elif ! node_version=$(env -u CVC_LLM_WORKER_TOKEN node --version) || [[ ! $node_version =~ ^v(22\.22\.([2-9]|[1-9][0-9]+)|22\.(2[3-9]|[3-9][0-9]|[1-9][0-9]{2,})\.[0-9]+|24\.15\.[0-9]+|24\.(1[6-9]|[2-9][0-9]|[1-9][0-9]{2,})\.[0-9]+)$ ]]; then
        echo 'SKIP frontend: Node must match ^22.22.2 || ^24.15.0'; frontend_ok=false; failed=true
    fi
fi
if ! $skip_ocr || $with_llm; then
    if ! have "$python" || ! python_version=$(env -u CVC_LLM_WORKER_TOKEN "$python" --version 2>&1) || [[ ! $python_version =~ ^Python\ 3\.14\. ]]; then
        echo 'SKIP workers: Python 3.14 is required'; ocr_ok=false; llm_ok=false; failed=true
    fi
fi
if $with_llm; then
    if ! have ollama || ! port_open 11434; then echo 'SKIP llm-worker: Ollama and port 11434 are required'; llm_ok=false; failed=true; fi
    if [[ -z $llm_token ]]; then echo 'SKIP llm-worker: session CVC_LLM_WORKER_TOKEN is missing; see docs/LOCAL_DEV.md'; llm_ok=false; fi
fi
if [[ $database != none ]]; then
    if ! have docker; then
        echo 'SKIP database/backend: Docker is required'; db_ok=false; failed=true
    elif [[ $database == dev ]]; then
        if ! env -u CVC_LLM_WORKER_TOKEN docker compose -f "$repo/infra/docker-compose.yml" up -d; then db_ok=false; failed=true; fi
    else
        if ! env -u CVC_LLM_WORKER_TOKEN docker inspect cv-compass-postgres-test >/dev/null 2>&1; then
            echo 'SKIP database/backend: create the test container with scripts/test-db.ps1 up on Windows'; db_ok=false; failed=true
        elif ! env -u CVC_LLM_WORKER_TOKEN docker start cv-compass-postgres-test; then db_ok=false; failed=true
        else
            healthy=false
            for ((i=0; i<60; i++)); do
                if [[ $(env -u CVC_LLM_WORKER_TOKEN docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' cv-compass-postgres-test) == healthy ]]; then healthy=true; break; fi
                sleep 2
            done
            if ! $healthy; then echo 'SKIP backend: test database did not become healthy'; db_ok=false; failed=true; fi
        fi
    fi
fi
mkdir -p -- "$run_dir"
printf '{"mode":"%s"}\n' "$database" > "$run_dir/database.json"
if ! $skip_backend && $backend_ok && $db_ok && can_start backend "$backend_port"; then
    build_ok=true
    if ! $skip_build; then
        if ! (cd -- "$repo/backend" && env -u CVC_LLM_WORKER_TOKEN ./gradlew bootJar --no-daemon); then build_ok=false; failed=true; fi
    fi
    if $build_ok; then
        shopt -s nullglob
        jars=()
        for jar in "$repo"/backend/build/libs/*.jar; do [[ $jar == *-plain.jar ]] || jars+=("$jar"); done
        if [[ ${#jars[@]} != 1 ]]; then
            echo 'SKIP backend: expected exactly one non-plain jar'; failed=true
        elif launch backend "$repo/backend" java '-Duser.timezone=UTC' -jar "${jars[0]}" --cv.import.enabled=false; then
            for ((i=0; i<wait_seconds; i++)); do
                if port_open "$backend_port"; then backend_ready=true; break; fi
                sleep 1
            done
            if ! $backend_ready; then echo 'SKIP dependents: backend port did not become ready; see backend log'; failed=true; fi
        fi
    fi
elif $skip_backend || $refused; then
    backend_ready=$(if port_open "$backend_port"; then echo true; else echo false; fi)
fi
if $backend_ready; then
    if ! $skip_frontend && $frontend_ok && can_start frontend "$frontend_port"; then
        launch frontend "$repo/frontend" npm run dev -- --port "$frontend_port" --strictPort || :
    fi
    if ! $skip_ocr && $ocr_ok && can_start ocr-worker; then
        launch ocr-worker "$repo" bash -c "$ocr_script" || :
    fi
    if $with_llm && $llm_ok && can_start llm-worker; then
        if (cd -- "$repo/llm-worker" || exit 1; export CVC_LLM_WORKER_TOKEN="$llm_token" PYTHONPATH=src; exec "$python" -m cv_compass_llm check); then
            launch llm-worker "$repo/llm-worker" bash -c 'export PYTHONPATH=src; exec "$1" -m cv_compass_llm pull' bash "$python" || :
        else
            code=$?; echo "SKIP llm-worker: check FAIL (exit $code)"; failed=true
        fi
    fi
else
    echo 'SKIP frontend/workers: backend is not ready'
fi
if ! $backend_ready && { ! $skip_frontend || ! $skip_ocr || $with_llm; }; then failed=true; fi
if ! $with_llm; then echo 'LLM worker is optional: set the session token and use --with-llm-worker; see docs/LOCAL_DEV.md section 2.'; fi
echo 'Logs: .dev/log/<svc>.log; records: .dev/run/<svc>.rec'
echo 'Stop: bash scripts/dev-down.sh (wait until no CV is PROCESSING)'
$refused && exit 3
$failed && exit 1
exit 0
