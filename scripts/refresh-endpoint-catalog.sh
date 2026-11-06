#!/usr/bin/env bash
# Refreshes the endpoint server's copy of the API's OpenAPI document and the catalog snapshot generated from it.
# Run from the repository root after changing anything the OpenAPI document describes, then commit both files.
# Once the benchmark is frozen, don't run it: the snapshot hash is part of the frozen setup.
set -euo pipefail

APP_PORT=18080
MCP_PORT=18081
DOC=mcp-endpoint/src/test/resources/openapi/order-support-api.json

mvn -q -pl app,mcp-endpoint -am package -DskipTests

java -jar app/target/app-0.1.0-SNAPSHOT.jar --server.port=$APP_PORT > /tmp/refresh-app.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  curl -sf "http://localhost:$APP_PORT/v3/api-docs" -o /tmp/order-support-api.json && break
  sleep 1
done
python3 -m json.tool --indent 2 /tmp/order-support-api.json > "$DOC"
echo "Wrote $DOC"

java -jar mcp-endpoint/target/mcp-endpoint-0.1.0-SNAPSHOT.jar --server.port=$MCP_PORT \
  --endpoint.api.base-url="http://localhost:$APP_PORT" --endpoint.catalog.mode=write > /tmp/refresh-mcp.log 2>&1 &
MCP_PID=$!
trap 'kill $APP_PID $MCP_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  grep -q "Wrote the catalog snapshot" /tmp/refresh-mcp.log && break
  sleep 1
done
grep "Wrote the catalog snapshot" /tmp/refresh-mcp.log
