#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
deploy_env="$project_root/deploy/.env"
compose=(
    docker compose
    --env-file "$deploy_env"
    -f "$project_root/deploy/compose.yaml"
    -f "$project_root/deploy/compose.app.yaml"
)

require_configuration() {
    test -f "$project_root/.env" || {
        echo "缺少 $project_root/.env" >&2
        exit 1
    }
    test -f "$deploy_env" || {
        echo "缺少 $deploy_env，请先从 deploy/.env.example 创建" >&2
        exit 1
    }
}

command="${1:-}"
case "$command" in
    up)
        require_configuration
        "${compose[@]}" up -d --build --wait
        "${compose[@]}" ps
        ;;
    build)
        require_configuration
        "${compose[@]}" build
        ;;
    restart)
        require_configuration
        "${compose[@]}" restart
        "${compose[@]}" ps
        ;;
    stop)
        require_configuration
        "${compose[@]}" stop
        ;;
    down)
        require_configuration
        "${compose[@]}" down
        ;;
    status)
        require_configuration
        "${compose[@]}" ps
        ;;
    health)
        require_configuration
        gateway_port="$(sed -n 's/^AGENT_GATEWAY_HOST_PORT=//p' "$deploy_env" | tail -1)"
        curl -fsS "http://127.0.0.1:${gateway_port:-8082}/health/ready"
        printf '\n'
        ;;
    logs)
        require_configuration
        shift
        "${compose[@]}" logs -f --tail 200 "$@"
        ;;
    test-integration)
        exec "$project_root/deploy/test-integration.sh"
        ;;
    *)
        echo "用法: deploy/app.sh {up|build|restart|stop|down|status|health|logs|test-integration}" >&2
        exit 2
        ;;
esac
