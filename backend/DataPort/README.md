# DataPort

该模块是 Java 后端访问业务存储的唯一入口，不提供面向前端或 Python 的 HTTP
Controller。`backend/Interface` 负责认证、开发期会话校验和 JSON 映射，再调用
本模块的 `MemoryDataPort` 与 `RagDataPort`。

## 存储分工

| 存储 | 用途 |
|---|---|
| MySQL | 长期记忆权威正文、来源、importance、状态、写操作幂等结果和 RAG 文档状态 |
| Qdrant | semantic / episodic 长期记忆向量索引，所有请求带 `user_id` payload filter |
| Milvus | RAG 分块正文和向量，检索及正文回查都限定 `user_id` 与 ready document_id |
| Neo4j | RAG 分块关系和语义记忆实体关系，节点身份包含 `user_id` |

Working Memory 仍在 Python 进程内按 `user_id + session_id` 管理，不进入 DataPort。
查询不会创建用户目录、记录或 collection；共享数据库结构只在部署初始化时建立。

## 初始化

1. 在 MySQL 执行 `src/main/resources/db/migration/V1__data_port.sql`。
2. 在 Neo4j 执行 `src/main/resources/db/neo4j-schema.cypher`。
3. 创建 Qdrant collection，默认名 `agent_memory_dev`，使用 768 维 Cosine 向量，
   并为 `user_id`、`status`、`memory_type`、`memory_id` 建 keyword payload index。
4. 创建 Milvus collection，默认名 `rag_chunks_dev`。字段为：
   `chunk_id`（VARCHAR 主键）、`user_id`、`document_id`、`chunk_index`、`text`、
   `source`、`file_type`、可空的 `page/h1/h2/h3`，以及 768 维 `vector`；向量索引
   使用 COSINE，`user_id` 和 `document_id` 是标量过滤字段。

维度 768 与当前 Python 嵌入模型 `jinaai/jina-embeddings-v2-base-zh` 一致。更换模型时必须
先建立新的开发 collection，不能把不同维度或不同模型的向量混入同一索引。

## 运行配置

`DataPortResources.fromEnvironment()` 使用以下环境变量组装数据库连接：

```text
MYSQL_JDBC_URL
MYSQL_USER
MYSQL_PASSWORD
MYSQL_POOL_SIZE=10

QDRANT_URL
QDRANT_API_KEY                 # 本地未启用认证时可省略
QDRANT_MEMORY_COLLECTION=agent_memory_dev

MILVUS_URL
MILVUS_TOKEN                   # 本地未启用认证时可省略
MILVUS_DATABASE=default
MILVUS_RAG_COLLECTION=rag_chunks_dev

NEO4J_URI
NEO4J_USER
NEO4J_PASSWORD
NEO4J_DATABASE=neo4j

DATA_PORT_TIMEOUT_SECONDS=15
```

开发身份由 `backend/User` 提供，当前固定为 `dev_user`；session ID 格式为
`session_yyyyMMdd_HHmmss_dev_user`。DataPort 不生成或信任默认身份，所有调用方
都必须显式传入非空 user ID。

## Interface 映射

`backend/Interface` 的内部 HTTP 接口只做认证、开发期会话校验和 JSON 到 Java
参数的映射：

| 内部接口 | DataPort 调用 |
|---|---|
| `/internal/storage/rag/search` | `rag().search(userId, queryVector, limit)` |
| `/internal/storage/rag/graph` | `rag().graphSearch(userId, seedChunkIds, queryVector, limit)` |
| RAG 构建结果入库 | `rag().replaceDocument(buildCommand)` |
| `/internal/storage/memory/query` | `memory().query(query)` |
| `/internal/storage/memory/store` | `memory().store(storeCommand)` |
| `/internal/storage/memory/forget` | `memory().forget(forgetCommand)` |

Memory 请求中的 `scope` 只允许为 `user`；Working Memory 由 Python 处理，不映射到
DataPort。`graph_status` 为 Python 图提炼状态，不作为数据库路由条件；只有实际的
`graph` 内容会进入 `StoreCommand`。Controller 不接受客户端提供的 SQL、Cypher
或用户过滤表达式。
