# Agent 通信接口

该模块处理 Java 与 Python Agent 之间的两个通信方向，并提供面向前端的 Agent
入口。数据库操作仍全部委托给 `backend/DataPort`，登录令牌由 `backend/User` 的
`UserService` 校验。`AgentSseClient` 只处理 Python 网络协议，`AgentRunService`
负责运行幂等与原始事件持久化，`AgentGatewayServer` 负责会话归属、聊天历史和
前端 SSE 转发。

## 前端调用 Java Agent 网关

所有接口都要求 `Authorization: Bearer <用户登录令牌>`：

| 方法与路径 | 用途 |
|---|---|
| `GET /sessions` | 读取当前用户的会话 |
| `POST /sessions` | 调用 Python 生成会话 ID；空会话仅登记在 Java 内存 |
| `DELETE /sessions/{session_id}` | 删除空会话或将已持久化会话标记为已删除 |
| `GET /sessions/{session_id}/messages` | 读取当前用户的可见问答与工具时间线 |
| `POST /sessions/{session_id}/runs` | 提交消息并转发 Python SSE |
| `POST /sessions/{session_id}/compact` | 使用 Java 组装的可信历史执行手动 Compact |

运行请求正文固定为：

```json
{
  "request_id": "由前端生成的稳定请求 ID",
  "message_id": "当前用户消息 ID",
  "message": "用户问题"
}
```

网关不接受正文中的 `user_id`。它从登录令牌取得用户身份，逐次检查会话归属，再把
可信身份、近期问答和当前有效摘要组装为 `AgentRunRequest`。首条用户消息、正式会话
归属和 Agent 运行会话在同一 MySQL 事务中创建；尚无消息的会话不会产生数据库记录。
`chat_session.session_id` 是全局主键，不能被另一用户重新绑定。

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
X-User-ID: <Java 已鉴权的 user_id>
X-Session-ID: session_yyyyMMdd_HHmmss_<user_id>
X-Request-ID: <request_id>
X-Message-ID: <message_id>  # Memory store 时必须提供
```

Interface 接受 Java User 服务分配的安全 `user_id`，也保留 `dev_user` 供测试使用。
Memory store 中的 `source.session_id` 与
`source.message_id` 必须等于可信请求上下文，Interface 使用可信值构造 DataPort
命令。模型不能通过业务参数切换用户或伪造来源。

## 启动内部数据接口

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

## 启动 Agent 网关

Agent 网关还需要 User、Python Agent 和上下文预算配置：

```text
AGENT_GATEWAY_HOST=127.0.0.1
AGENT_GATEWAY_PORT=8082
AGENT_GATEWAY_ALLOWED_ORIGIN=http://localhost:5173
PYTHON_AGENT_BASE_URL=http://127.0.0.1:8800
PYTHON_INTERNAL_TOKEN=<与 Python 服务相同的内部 token>
USER_TOKEN_TTL_HOURS=168

AGENT_MODEL_WINDOW=128000
AGENT_MAX_CONTEXT_TOKENS=100000
AGENT_OUTPUT_RESERVE=8000
AGENT_SAFETY_MARGIN=4000
AGENT_TOOL_RESULT_RESERVE=8000
AGENT_COMPACT_TRIGGER_RATIO=0.92
AGENT_SUMMARY_MAX_TOKENS=2000
AGENT_KEEP_RECENT_TURNS=5
```

`MYSQL_JDBC_URL`、`MYSQL_USER` 和 `MYSQL_PASSWORD` 与 UserServer 相同。构建后的同一
个 Interface fat jar 包含两个入口：

```bash
# 内部数据接口（jar 默认 Main-Class）
java -jar Interface/target/agent-interface-0.1.0-SNAPSHOT.jar

# 面向前端的 Agent 网关
java -cp Interface/target/agent-interface-0.1.0-SNAPSHOT.jar \
  com.pdflearning.backend.interfaceapi.AgentGatewayServer
```

User 模块的普通 jar 供 Interface 编译依赖，独立服务使用
`java -jar User/target/user-0.1.0-SNAPSHOT-all.jar` 启动。这样 Interface 打包时不会
再次嵌套 User 的全部第三方依赖。

只有 AgentGatewayServer 启动时恢复遗留的 `running` 记录。重启内部数据接口不会
中断仍在网关中执行的运行。

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
可能发生过的工具写操作不会自动重试。`AgentGatewayServer` 启动时会把上次进程遗留的
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
