# 部署与运行

项目使用 Docker Compose 统一运行 MySQL、Milvus、Neo4j、4 个 Python 服务和
2 个 Java 服务。Compose 负责启动顺序、应用健康检查、日志收集和异常退出后的
自动重启。生产环境默认连接 Qdrant Cloud；真实集成测试使用隔离的本地 Qdrant。

## 首次配置

模型配置放在项目根目录 `.env`。数据库密码、内部 token、服务端口和 Qdrant
配置放在 `deploy/.env`：

```bash
cp deploy/.env.example deploy/.env
```

至少替换示例中的 MySQL、MinIO、Neo4j 密码，三个内部 token，以及
`QDRANT_URL`、`QDRANT_API_KEY`。Qdrant collection 使用 768 维 COSINE，并为
`user_id`、`memory_id`、`memory_type`、`status` 建立 keyword payload index。

应用容器共享 `uploads-data` volume。Java 网关把用户文件写入 `/data/uploads`，
Python RAG 构建服务从同一路径读取，不需要宿主机路径映射。

## 统一启动

```bash
./deploy/app.sh up
```

该命令构建 Python、Java 镜像，启动基础设施和应用，并等待所有健康检查通过。
浏览器公开 API 位于 `http://127.0.0.1:8082`。其余应用端口只在 Compose 网络中
开放；生产环境由 Nginx 提供 `Fronted/dist`，并把公开 API 转发到 8082。
需要修改宿主机公开端口时设置 `AGENT_GATEWAY_HOST_PORT`；容器内服务端口保持固定。

应用启动顺序为：

```text
MySQL / Milvus / Neo4j
        ↓
Java Storage
        ↓
RAG MCP / Memory MCP + RAG Build API
        ↓
Python Agent API
        ↓
Java Gateway
```

所有常驻服务使用 `restart: unless-stopped`。数据库必须通过健康检查且初始化任务
成功后，Java Storage 才会启动；上游服务未就绪时，下游不会被标记为健康。

## 健康检查

每个应用提供两类端点：

| 端点 | 含义 |
|---|---|
| `GET /health/live` | 进程正在响应，不检查依赖 |
| `GET /health/ready` | 当前服务及其必要依赖可以处理请求 |

Java Storage 的 readiness 会实际访问 MySQL、Milvus collection、Neo4j 和 Qdrant
collection。RAG/Memory MCP 检查 Java Storage，Agent API 检查 Java Storage 和
两个 MCP 服务；Java Gateway 汇总数据层、Agent API 与 RAG Build API 状态。

统一查看最终状态：

```bash
./deploy/app.sh health
./deploy/app.sh status
```

readiness 返回 503 时，响应中的 `components` 会标出不可用组件。接口不会返回连接
地址、密码或 token。

## 运维命令

```bash
./deploy/app.sh logs
./deploy/app.sh logs agent-api java-gateway
./deploy/app.sh restart
./deploy/app.sh stop
./deploy/app.sh down
```

`down` 删除容器和网络，保留数据库与上传文件的 named volume。只有明确需要清空
数据时才手动执行带 `-v` 的 Compose 命令。

基础设施仍可单独启动，供宿主机调试：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
```

MySQL、Milvus 和 Neo4j 的宿主机端口均绑定到 `127.0.0.1`，具体端口由
`deploy/.env` 配置。etcd 和 MinIO 只在数据库网络内开放。

## 真实数据存储集成测试

```bash
./deploy/app.sh test-integration
```

测试脚本使用固定 Compose 项目名 `pdf-learning-it` 和独立端口、collection、数据卷，
自动完成以下步骤：

1. 启动真实 MySQL、Milvus、Neo4j、Qdrant。
2. 执行各数据库 schema/collection 初始化。
3. 通过 Maven Failsafe 运行 `RealDataStoresIT`。
4. 验证四个存储 readiness、RAG 写入/检索/图扩展、Memory 写入/检索/删除，以及
   两个用户之间的查询隔离。
5. 测试结束后删除 `pdf-learning-it` 容器和数据卷。

调试失败现场时可保留测试环境：

```bash
KEEP_INTEGRATION_DATA=1 ./deploy/test-integration.sh
```

测试环境使用 [`.env.integration`](.env.integration) 中的非生产凭据。脚本在每条
Compose 命令上显式指定 `--project-name pdf-learning-it`，不会操作开发项目的数据卷。

## 数据初始化

- MySQL 首次创建数据卷时执行 `mysql-schema.sql`。
- Milvus 初始化配置的 RAG collection 和 COSINE 索引。
- Neo4j 初始化用户范围唯一约束。
- 本地 Qdrant 由 `local-qdrant` profile 启动并自动建立 collection 与 payload index；
  该 profile 默认只用于集成测试。

embedding 维度改变后必须使用新的 Milvus 和 Qdrant collection 名，不能把不同维度
的向量写入同一个 collection。

空库的 MySQL、Milvus、etcd、MinIO、Neo4j 约需要 1.5 GiB 内存。应用还会加载
文档解析和 embedding 模型，部署主机建议至少 8 GiB 内存。
