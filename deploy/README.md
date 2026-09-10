# 基础设施容器部署

Docker 只运行项目依赖的本地基础设施：MySQL、Milvus、Neo4j，以及 Milvus 需要的 etcd 和 MinIO。Python Agent、Java 后端和前端直接运行在宿主机上；Qdrant 继续使用 Qdrant Cloud。

MySQL、Milvus 和 Neo4j 的端口只绑定 `127.0.0.1`，宿主机应用可以访问，公网无法直接访问。持久数据保存在 Docker named volume 中，更新或重建容器不会删除数据。

## 启动基础设施

首次使用时创建私有配置并修改其中的密码、内部 token 和 Qdrant Cloud 配置：

```bash
cp deploy/.env.example deploy/.env
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
```

| 宿主机地址 | 服务 |
|---|---|
| `127.0.0.1:3307` | MySQL；使用 3307 避免和已有本机 MySQL 冲突 |
| `http://127.0.0.1:19530` | Milvus REST/gRPC |
| `http://127.0.0.1:9091` | Milvus 健康接口 |
| `http://127.0.0.1:7474` | Neo4j Browser |
| `bolt://127.0.0.1:7687` | Neo4j Bolt |

etcd 和 MinIO 只供 Milvus 在 Docker 网络中访问，不映射宿主机端口。Qdrant Cloud 由 Java 后端通过 `QDRANT_URL` 和 `QDRANT_API_KEY` 直接访问。如果端口被其他服务占用，可以在 `deploy/.env` 中修改 `NEO4J_HTTP_PORT`、`NEO4J_BOLT_PORT`，并同步修改 `NEO4J_URI`。

## 初始化内容

- MySQL 第一次创建数据卷时依次执行 `V1__data_port.sql` 和
  `V2__agent_run.sql`；后者创建 Agent 会话、运行和事件表。
- Milvus 初始化 `rag_chunks_dev` 的完整 RAG 分块 schema 和 768 维 COSINE 索引。
- Neo4j 执行项目现有的用户范围唯一约束。
- Qdrant Cloud 中的 `agent_memory_dev` collection 仍需提前建立为 768 维 COSINE collection，并为 `user_id`、`memory_id`、`memory_type`、`status` 建立 keyword payload index。

初始化脚本只创建不存在的 Milvus collection。修改 embedding 维度后，应使用新 collection 名，不能把不同维度的向量写入同一 collection。

MySQL 的 `/docker-entrypoint-initdb.d` 只在首次创建数据卷时执行。已有开发数据卷需要
从项目根目录手动应用新增迁移：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T mysql \
  sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < backend/DataPort/src/main/resources/db/migration/V2__agent_run.sql
```

## 云服务器配置

当前空库状态下，MySQL、Milvus、etcd、MinIO、Neo4j 合计约占 1.44 GiB 内存。宿主机还要运行 Java、四个 Python 进程、Jina embedding 模型、前端服务和操作系统，因此建议从 8 GiB 内存起步；4 GiB 容量容易在模型加载或文档建库时触发内存不足。

## 运行宿主机应用

模型配置继续放在项目根目录的 `.env`；数据库地址、密码、内部 token 和 Qdrant Cloud 配置放在 `deploy/.env`。启动 Java 或 Python 前，将两份配置导入当前终端：

```bash
set -a
. ./.env
. ./deploy/.env
set +a
```

Java 数据接口：

```bash
/home/aupt/.local/opt/apache-maven-3.9.9/bin/mvn -f backend/pom.xml -pl Interface -am -DskipTests package
java -jar backend/Interface/target/agent-interface-0.1.0-SNAPSHOT.jar
```

Python 进程分别运行：

```bash
python -m Agent.Tools.MCP.RAGServer --transport streamable-http
python -m Agent.Tools.MCP.MemoryServer --transport streamable-http
uvicorn Agent.Interface.AgentAPI:app --host 127.0.0.1 --port 8800
uvicorn Agent.Interface.RAGBuildAPI:app --host 127.0.0.1 --port 8803
```

RAG 上传文件由 Java 放入 `RAG_ALLOWED_ROOT` 指定的宿主机目录，Python RAG 构建接口从同一路径读取。

前端在 `Fronted` 目录执行 `npm ci && npm run build`。云服务器正式运行时由宿主机 Nginx 提供 `Fronted/dist`，并把公开 API 转发到后续完成的 Java 用户网关。当前 Java `Interface` 仍是 Python 到数据层的内部接口，尚未实现面向前端的用户网关和 Java 到 Agent SSE 的转发。

## 运维命令

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml logs -f
docker compose --env-file deploy/.env -f deploy/compose.yaml restart mysql milvus neo4j
docker compose --env-file deploy/.env -f deploy/compose.yaml down
```

`down` 保留 named volume。只有明确要清空全部开发数据时才执行：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml down -v
```
