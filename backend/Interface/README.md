# Agent 内部接口

该模块处理 Java 与 Python Agent 之间的两个通信方向：Python 调用 Java 数据服务，
以及 Java 调用 Python Agent SSE 运行接口。数据库操作仍全部委托给
`backend/DataPort`。`AgentSseClient` 只处理网络协议，`AgentRunService` 负责运行
幂等与事件持久化；登录鉴权和面向前端的 Controller 仍由后续用户网关负责。

## Python 调用 Java 数据服务

| 方法与路径 | DataPort 能力 |
|---|---|
| `POST /internal/storage/rag/search` | RAG 向量检索 |
| `POST /internal/storage/rag/graph` | RAG 图扩展候选检索 |
| `POST /internal/storage/memory/query` | 用户级长期记忆查询 |
| `POST /internal/storage/memory/store` | 长期记忆新增或纠正 |
| `POST /internal/storage/memory/forget` | 长期记忆删除 |
| `POST /internal/storage/context/summary` | 会话摘要的版本化、幂等保存 |

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

`AgentControlClient.createSession` 取得 Python 生成的 session ID；
`compactContext` 提交可信历史快照并解析手动 Compact 结果。
`AgentSseClient.openRun` 调用 `POST /internal/agent/runs` 并打开事件流。运行调用负责：

- 将 `AgentRunRequest` 序列化为 Python `AgentRunRequest` 接受的 JSON；
- 绑定内部 Bearer token 和四个可信运行标识请求头；
- 增量解析 `event` / `data` SSE 帧；
- 校验事件的 request_id、session_id 和连续 event_seq；
- 在连接未收到 run_finished 就结束时报告中断。

业务入口通过 `AgentRunService.execute` 调用。服务会先按 request ID 登记运行，逐条
把完整事件 JSON 落库，成功后才交给 consumer 转发：

```java
var client = AgentSseClient.fromEnvironment();
var service = new AgentRunService(client, resources.agentRuns());
var result = service.execute(runRequest, event -> forwardToFrontend(event));
```

`RunResult.outcome` 的含义如下：

| outcome | 行为 |
|---|---|
| `EXECUTED` | 本次取得执行权，已调用 Python 并持久化事件 |
| `REPLAYED` | 请求已结束，按序回放数据库事件，未再次调用 Python |
| `IN_PROGRESS` | 相同请求正在执行 |
| `SESSION_BUSY` | 同会话的另一请求正在执行 |
| `TERMINAL` | 请求曾中断，保留记录且不自动重放 |

相同 request ID 若携带不同请求内容、session ID 或 message ID，会作为冲突拒绝。
Python 流提前结束、消费端断开或事件持久化失败时，运行会标记为 `interrupted`；已知
可能发生过的工具写操作不会自动重试。`AgentStorageServer` 启动时会把上次进程遗留的
`running` 记录改为 `interrupted` 并释放会话。

客户端环境变量：

```text
PYTHON_AGENT_BASE_URL=http://127.0.0.1:8800
PYTHON_INTERNAL_TOKEN=<与 Python 服务相同的内部 token>
```

`AgentRunRequest` 中 `recentHistory` 和 `executionHistory` 使用 JsonNode，是因为这些
字段由聊天与执行记录查询结果组装，内部结构已经由 Python 的网络契约固定；身份字段和
ContextConfig 使用明确的 Java 字段。`AgentEventStream` 是阻塞式增量读取器，不会把
完整回答缓存到内存后再返回。
