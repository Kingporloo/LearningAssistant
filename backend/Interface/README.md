# Agent 内部接口

该模块处理 Java 与 Python Agent 之间的两个通信方向：Python 调用 Java 数据服务，
以及 Java 调用 Python Agent SSE 运行接口。数据库操作仍全部委托给
`backend/DataPort`，SSE 客户端不负责登录鉴权、事件持久化或前端转发。

## Python 调用 Java 数据服务

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

## Java 调用 Python Agent

`AgentSseClient` 调用 Python 的 `POST /internal/agent/runs`。它负责：

- 将 `AgentRunRequest` 序列化为 Python `AgentRunRequest` 接受的 JSON；
- 绑定内部 Bearer token 和四个可信运行标识请求头；
- 增量解析 `event` / `data` SSE 帧；
- 校验事件的 request_id、session_id 和连续 event_seq；
- 在连接未收到 run_finished 就结束时报告中断。

调用方应在 Java 虚拟线程中消费事件，并用 try-with-resources 保证前端断开或运行
结束时关闭到 Python 的响应流：

```java
var client = AgentSseClient.fromEnvironment();
try (var stream = client.openRun(runRequest)) {
    stream.consume(event -> {
        // 后续阶段在这里幂等保存事件，并转发给前端。
    });
}
```

客户端环境变量：

```text
PYTHON_AGENT_BASE_URL=http://127.0.0.1:8800
PYTHON_INTERNAL_TOKEN=<与 Python 服务相同的内部 token>
```

`AgentRunRequest` 中 `recentHistory` 和 `executionHistory` 使用 JsonNode，是因为这些
字段由聊天与执行记录查询结果组装，内部结构已经由 Python 的网络契约固定；身份字段和
ContextConfig 使用明确的 Java 字段。`AgentEventStream` 是阻塞式增量读取器，不会把
完整回答缓存到内存后再返回。
