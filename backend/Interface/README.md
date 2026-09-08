# Agent 数据接口

该模块是 Java 后端面向 Python Agent 的内部 HTTP 接口。它只负责内部认证、可信
运行上下文校验和 JSON 映射，实际数据库操作全部委托给 `backend/DataPort`。

## 接口

| 方法与路径 | DataPort 能力 |
|---|---|
| `POST /internal/storage/rag/search` | RAG 向量检索 |
| `POST /internal/storage/rag/graph` | RAG 图扩展候选检索 |
| `POST /internal/storage/memory/query` | 用户级长期记忆查询 |
| `POST /internal/storage/memory/store` | 长期记忆新增或纠正 |
| `POST /internal/storage/memory/forget` | 长期记忆删除 |

请求格式与 Python `Agent/Interface/BackendClient.py` 保持一致。每个请求必须同时
携带以下请求头和同名 JSON 字段，两个位置的值必须一致：

```text
Authorization: Bearer <JAVA_INTERNAL_TOKEN>
X-User-ID: dev_user
X-Session-ID: session_yyyyMMdd_HHmmss_dev_user
X-Request-ID: <request_id>
X-Message-ID: <message_id>  # Memory store 时必须提供
```

开发阶段只接受 `dev_user`。Memory store 中的 `source.session_id` 与
`source.message_id` 必须等于可信请求上下文，Interface 使用可信值构造 DataPort
命令。模型不能通过业务参数切换用户或伪造来源。

## 启动

除 DataPort 的数据库环境变量外，还需要：

```text
JAVA_INTERNAL_TOKEN=<与 Python JAVA_INTERNAL_TOKEN 相同的随机值>
JAVA_INTERFACE_HOST=127.0.0.1
JAVA_INTERFACE_PORT=8080
```

构建并运行：

```bash
cd backend
mvn package
java -jar Interface/target/agent-interface-0.1.0-SNAPSHOT.jar
```

Python 侧将 `JAVA_STORAGE_BASE_URL` 设置为 `http://127.0.0.1:8080`。服务默认只
监听本机地址；对外部署时由 Java 网关或反向代理处理外部访问，不能直接暴露这些
内部存储接口。
