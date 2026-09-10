#!/bin/sh
set -eu

base_url="http://milvus:19530"

response="$(curl -fsS -X POST \
    -H "Content-Type: application/json" \
    "$base_url/v2/vectordb/collections/has" \
    -d "{\"dbName\":\"${MILVUS_DATABASE}\",\"collectionName\":\"${MILVUS_RAG_COLLECTION}\"}")"

if printf '%s' "$response" | grep -Eq '"has"[[:space:]]*:[[:space:]]*true'; then
    exit 0
fi

curl -fsS -X POST \
    -H "Content-Type: application/json" \
    "$base_url/v2/vectordb/collections/create" \
    -d "{
      \"dbName\": \"${MILVUS_DATABASE}\",
      \"collectionName\": \"${MILVUS_RAG_COLLECTION}\",
      \"schema\": {
        \"autoID\": false,
        \"enableDynamicField\": false,
        \"fields\": [
          {\"fieldName\":\"chunk_id\",\"dataType\":\"VarChar\",\"isPrimary\":true,\"elementTypeParams\":{\"max_length\":256}},
          {\"fieldName\":\"user_id\",\"dataType\":\"VarChar\",\"elementTypeParams\":{\"max_length\":128}},
          {\"fieldName\":\"document_id\",\"dataType\":\"VarChar\",\"elementTypeParams\":{\"max_length\":160}},
          {\"fieldName\":\"chunk_index\",\"dataType\":\"Int64\"},
          {\"fieldName\":\"text\",\"dataType\":\"VarChar\",\"elementTypeParams\":{\"max_length\":65535}},
          {\"fieldName\":\"source\",\"dataType\":\"VarChar\",\"elementTypeParams\":{\"max_length\":2048}},
          {\"fieldName\":\"file_type\",\"dataType\":\"VarChar\",\"elementTypeParams\":{\"max_length\":32}},
          {\"fieldName\":\"page\",\"dataType\":\"Int64\",\"nullable\":true},
          {\"fieldName\":\"h1\",\"dataType\":\"VarChar\",\"nullable\":true,\"elementTypeParams\":{\"max_length\":4096}},
          {\"fieldName\":\"h2\",\"dataType\":\"VarChar\",\"nullable\":true,\"elementTypeParams\":{\"max_length\":4096}},
          {\"fieldName\":\"h3\",\"dataType\":\"VarChar\",\"nullable\":true,\"elementTypeParams\":{\"max_length\":4096}},
          {\"fieldName\":\"vector\",\"dataType\":\"FloatVector\",\"elementTypeParams\":{\"dim\":${EMBEDDING_VECTOR_DIMENSION}}}
        ]
      },
      \"indexParams\": [
        {\"fieldName\":\"vector\",\"indexName\":\"vector_index\",\"metricType\":\"COSINE\",\"params\":{\"index_type\":\"AUTOINDEX\"}}
      ]
    }" >/dev/null

