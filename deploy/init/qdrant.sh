#!/bin/sh
set -eu

base_url="http://qdrant:6333"
collection="${QDRANT_MEMORY_COLLECTION}"

attempt=0
until curl -fsS "$base_url/healthz" >/dev/null; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
        echo "Qdrant 未在规定时间内就绪" >&2
        exit 1
    fi
    sleep 1
done

status="$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/collections/$collection")"
if [ "$status" = "404" ]; then
    curl -fsS -X PUT \
        -H "Content-Type: application/json" \
        "$base_url/collections/$collection" \
        -d "{\"vectors\":{\"size\":${EMBEDDING_VECTOR_DIMENSION},\"distance\":\"Cosine\"}}" \
        >/dev/null
elif [ "$status" != "200" ]; then
    echo "无法读取 Qdrant collection，HTTP $status" >&2
    exit 1
fi

for field in user_id memory_id memory_type status; do
    curl -fsS -X PUT \
        -H "Content-Type: application/json" \
        "$base_url/collections/$collection/index?wait=true" \
        -d "{\"field_name\":\"$field\",\"field_schema\":\"keyword\"}" \
        >/dev/null
done
