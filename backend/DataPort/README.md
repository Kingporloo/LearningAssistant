# DataPort

该模块是 Java 后端访问业务存储的唯一入口，不提供面向前端或 Python 的 HTTP
Controller。`backend/Interface` 负责认证、开发期会话校验和 JSON 映射，再调用
本模块的 `MemoryDataPort` 与 `RagDataPort`。

## 存储分工

| 存储 | 用途 |
|---|---|
| MySQL | 用户、登录令牌、长期记忆正文、RAG 文档状态，以及 Agent 会话、运行和原始 SSE 事件 |
| Qdrant | semantic / episodic 长期记忆向量索引，所有请求带 `user_id` payload filter |
| Milvus | RAG 分块正文和向量，检索及正文回查都限定 `user_id` 与 ready document_id |
| Neo4j | RAG 分块关系和语义记忆实体关系，节点身份包含 `user_id` |

Working Memory 仍在 Python 进程内按 `user_id + session_id` 管理，不进入 DataPort。
查询不会创建用户目录、记录或 collection；共享数据库结构只在部署初始化时建立。

## 初始化

1. 在 MySQL 依次执行 `src/main/resources/db/migration/V1__data_port.sql`、
   `V2__agent_run.sql`、`V3__context_summary.sql`、`V4__user.sql` 和
   `V5__chat.sql`。
2. 在 Neo4j 执行 `src/main/resources/db/neo4j-schema.cypher`。
3. 创建 Qdrant collection，默认名 `agent_memory_dev`，使用 768 维 Cosine 向量，
   并为 `user_id`、`status`、`memory_type`、`memory_id` 建 keyword payload index。
4. 创建 Milvus collection，默认名 `rag_chunks_dev`。字段为：
   `chunk_id`（VARCHAR 主键）、`user_id`、`document_id`、`chunk_index`、`text`、
   `source`、`file_type`、可空的 `page/h1/h2/h3`，以及 768 维 `vector`；向量索引
   使用 COSINE，`user_id` 和 `document_id` 是标量过滤字段。

Docker Compose 的初始化脚本只会在全新的 MySQL 数据卷上自动执行。已有开发数据卷
需要手动执行尚未应用的 migration。

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

EMBEDDING_VECTOR_DIMENSION=768

NEO4J_URI
NEO4J_USER
NEO4J_PASSWORD
NEO4J_DATABASE=neo4j

DATA_PORT_TIMEOUT_SECONDS=15
```

`EMBEDDING_VECTOR_DIMENSION` 必须同时匹配 Python 嵌入模型、Qdrant collection 和
Milvus collection。若现有 collection 按旧模型创建为其他维度，应新建 768 维
collection 并修改上述 collection 名称；旧向量不能直接复用，需要用新模型重新生成。

正式身份由 `backend/User` 分配，`dev_user` 只保留给开发测试；session ID 格式为
`session_yyyyMMdd_HHmmss_<user_id>`。DataPort 不生成或信任默认身份，所有调用方
都必须显式传入非空 user ID。用户表和令牌表由 `V4__user.sql` 初始化，User 模块
通过 `UserDataPort` 访问，不直接执行 JDBC 或建表。

## Agent 运行与事件

`AgentRunDataPort` 在一个事务中处理会话占用、运行状态和事件序号：

| 表 | 主键 | 用途 |
|---|---|---|
| `agent_session` | `user_id + session_id` | 保存当前占用会话的 request ID |
| `agent_run` | `user_id + request_id` | 保存请求摘要、运行状态和最后事件序号 |
| `agent_run_event` | `user_id + request_id + event_seq` | 保存 Python 发出的完整事件 JSON |
| `session_summary` | `user_id + session_id` | 保存当前会话摘要及版本 |
| `context_summary_operation` | `user_id + request_id + operation_id` | 保存摘要写入的幂等结果 |
| `chat_session` | 全局唯一 `session_id` | 保存正式会话归属、标题和删除状态 |
| `chat_message` | 全局唯一 `message_id` | 保存用户可见问答及助手工具时间线 |

同一 request ID 只有用户消息摘要、session ID 和 message ID 全部一致时才视为重试。
历史、摘要和运行配置是 Java 派生的执行快照，不参与重试判断，避免运行完成后历史变化
导致合法重试被误判为冲突。
相同事件序号和内容重复写入时直接复用；序号相同但内容不同会报冲突。结束事件与
运行终态在同一事务提交，并只释放属于该请求的会话占用。

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
| `/internal/storage/context/summary` | `contextSummaries().store(storeCommand)` |

Java 调用 Python Agent 时，`backend/Interface` 使用 `agentRuns()` 先登记运行，再逐条
保存 SSE 事件。该端口不负责网络调用或前端转发。

Memory 请求中的 `scope` 只允许为 `user`；Working Memory 由 Python 处理，不映射到
DataPort。`graph_status` 为 Python 图提炼状态，不作为数据库路由条件；只有实际的
`graph` 内容会进入 `StoreCommand`。Controller 不接受客户端提供的 SQL、Cypher
或用户过滤表达式。
