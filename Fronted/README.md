# React + TypeScript + Vite

## 后端接口配置

前端默认使用 Mock。联调用户服务时复制 `.env.example` 为 `.env.local`，设置
`VITE_USE_MOCK=false`。`VITE_USER_API_BASE_URL` 指向 UserServer，Agent 网关可以通过
`VITE_API_BASE_URL` 单独配置；统一网关部署时将两者设为同一地址。

当前本地默认端口为：UserServer `http://127.0.0.1:8081`，AgentGatewayServer
`http://127.0.0.1:8082`。真实聊天请求会把前端创建的 `request_id` 和
`message_id` 一并发送，用于 Java 运行幂等和消息关联。

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
