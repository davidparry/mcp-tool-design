#!/usr/bin/env bash
# Starts or stops the benchmark stack from the repo root:
#   the app with the benchmark profile on 8080 (admin endpoints on, call log tagged by run),
#   the endpoint MCP server on 8081 (refuses to start unless its catalog matches the frozen snapshot),
#   the workflow MCP server on 8082.
# Usage: scripts/bench-stack.sh start [--no-build] | up [--no-build] | stop | status
#   start runs the servers in the background; up runs them in the foreground until Ctrl-C.
# Logs go to results/logs/.
# STACK_PORT=9080 runs a second stack on 9080, 9081, and 9082, with its own logs and pid file, so
# two batches can run at once. Start it with --no-build while another stack is running.
set -euo pipefail
cd "$(dirname "$0")/.."

APP_PORT=${STACK_PORT:-8080}
ENDPOINT_PORT=$((APP_PORT + 1))
WORKFLOW_PORT=$((APP_PORT + 2))
SUFFIX=""
if [[ $APP_PORT != 8080 ]]; then
  SUFFIX="-$APP_PORT"
fi
LOGS=results/logs
PIDS=$LOGS/stack$SUFFIX.pids
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java

wait_for() {
  local name=$1 check=$2
  for _ in $(seq 1 90); do
    if eval "$check" > /dev/null 2>&1; then
      echo "$name is up"
      return 0
    fi
    sleep 1
  done
  echo "$name didn't start; see $LOGS" >&2
  return 1
}

start() {
  if [[ -f $PIDS ]]; then
    echo "The stack looks like it's running ($PIDS exists). Run '$0 stop' first." >&2
    exit 1
  fi
  mkdir -p "$LOGS"
  if [[ "${1:-}" != "--no-build" ]]; then
    mvn -q -pl frontend,app,mcp-endpoint,mcp-workflow package -DskipTests
  fi
  "$JAVA" -jar app/target/app-0.1.0-SNAPSHOT.jar --spring.profiles.active=benchmark --server.port="$APP_PORT" \
    > "$LOGS/app$SUFFIX.log" 2>&1 &
  echo $! > "$PIDS"
  wait_for app "curl -sf http://localhost:$APP_PORT/v3/api-docs"
  APP_BASE_URL=http://localhost:$APP_PORT "$JAVA" -jar mcp-endpoint/target/mcp-endpoint-0.1.0-SNAPSHOT.jar \
    --spring.profiles.active=benchmark --server.port="$ENDPOINT_PORT" > "$LOGS/endpoint$SUFFIX.log" 2>&1 &
  echo $! >> "$PIDS"
  APP_BASE_URL=http://localhost:$APP_PORT "$JAVA" -jar mcp-workflow/target/mcp-workflow-0.1.0-SNAPSHOT.jar \
    --server.port="$WORKFLOW_PORT" > "$LOGS/workflow$SUFFIX.log" 2>&1 &
  echo $! >> "$PIDS"
  wait_for "endpoint server" "nc -z localhost $ENDPOINT_PORT"
  wait_for "workflow server" "nc -z localhost $WORKFLOW_PORT"
}

stop() {
  if [[ -f $PIDS ]]; then
    while read -r pid; do
      kill "$pid" 2> /dev/null || true
    done < "$PIDS"
    rm -f "$PIDS"
    echo "Stopped"
  else
    echo "Nothing to stop"
  fi
}

status() {
  for port in "$APP_PORT" "$ENDPOINT_PORT" "$WORKFLOW_PORT"; do
    if nc -z localhost $port 2> /dev/null; then echo "$port up"; else echo "$port down"; fi
  done
}

case "${1:-}" in
  start) start "${2:-}" ;;
  up)
    trap stop INT TERM EXIT
    start "${2:-}"
    wait
    ;;
  stop) stop ;;
  status) status ;;
  *) echo "Usage: $0 start [--no-build] | up [--no-build] | stop | status" >&2; exit 2 ;;
esac
