# React + TypeScript + Vite

## 后端接口配置

前端默认连接真实 Java 网关。复制 `.env.example` 为 `.env.local` 后，通过
`VITE_API_BASE_URL` 配置网关地址；只有显式设置 `VITE_USE_MOCK=true` 时才使用
浏览器内 Mock 数据。

当前本地网关地址为 `http://127.0.0.1:8082`。它统一提供用户、会话、Agent 和
RAG 文档接口。文档页把 PDF、Markdown 或 TXT 原始字节提交给该网关，并轮询
Java 保存的转换和建库状态。真实聊天请求会把前端创建的 `request_id` 和
`message_id` 一并发送，用于 Java 运行幂等和消息关联。

## 浏览器端到端测试

测试使用 Playwright 和 Chrome，前端保持默认的真实 API 客户端；测试进程在浏览器
网络层提供确定性的 Java 网关响应，因此会实际覆盖 HTTP 请求、鉴权头和 SSE 解析，
不依赖模型或数据库状态。

首次运行先安装依赖并确保本机有 Chrome：

```bash
npm install
npm run test:e2e
```

当前用例覆盖注册与受保护路由、创建会话与 Agent SSE 工具调用、手动 Compact，
以及文档上传、建库轮询、重建和删除。失败时截图、trace 和 HTML 报告会写入
`test-results/` 与 `playwright-report/`。

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.
