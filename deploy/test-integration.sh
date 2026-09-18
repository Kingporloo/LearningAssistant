#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$project_root/deploy/.env.integration"
compose=(
    docker compose
    --project-name pdf-learning-it
    --env-file "$env_file"
    -f "$project_root/deploy/compose.yaml"
    --profile local-qdrant
)

cleanup() {
    status=$?
    if [ "$status" -ne 0 ]; then
        "${compose[@]}" logs --tail 100 || true
    fi
    if [ "${KEEP_INTEGRATION_DATA:-0}" != "1" ]; then
        "${compose[@]}" down -v --remove-orphans || true
    fi
    exit "$status"
}
trap cleanup EXIT

"${compose[@]}" down -v --remove-orphans
"${compose[@]}" up -d mysql milvus neo4j qdrant
"${compose[@]}" up milvus-init neo4j-init qdrant-init

set -a
# shellcheck disable=SC1090
. "$env_file"
set +a
export RUN_REAL_DATASTORE_TESTS=1

"${MAVEN_BIN:-/home/aupt/.local/opt/apache-maven-3.9.9/bin/mvn}" \
    -f "$project_root/backend/pom.xml" \
    -pl DataPort -am -Pintegration verify
