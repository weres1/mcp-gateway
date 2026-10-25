# MCP Gateway（Java）

把多个上游 MCP Server 聚合成**一个** MCP 端点。客户端（Cursor 等）只连网关，就能用到所有上游的工具。

- Java 21 + Spring Boot 3.5 + WebFlux
- 官方 MCP Java SDK `io.modelcontextprotocol.sdk:mcp-core:2.1.0`（连接上游用）
- 控制台 UI：部署上游、按服务/密钥限制工具、查看调用

## 功能

1. 在控制台或 `application.yml` 中配置多个上游，支持 `stdio`、`streamable_http`、`sse` 三种传输
2. 聚合所有上游工具，以 `服务名__工具名` 暴露（如 `everything__echo`）
3. `tools/call` 按前缀路由到对应上游
4. API Key 鉴权：`Authorization: Bearer <key>` 或 `X-API-Key: <key>`
5. 每次工具调用都打日志（logger 名 `tool-call`），并在控制台「调用记录」可见
6. **控制台**（`http://localhost:8080/`）
   - 部署常见 MCP（Filesystem / Memory / Fetch / Git / Playwright / GitHub 远程 / 自定义）
   - 连接、断开、删除上游
   - 按上游：工具允许列表、拒绝列表（支持 `*` 后缀）、每分钟限流
   - 按 API Key：可见上游、可见工具、每分钟限流
   - 密钥只在创建时显示一次

运行时配置写在 `${GATEWAY_DATA_DIR:~/.mcp-gateway}/runtime.json`。第一次启动会把 `application.yml` 的上游和 api-keys **播种**进去；之后以控制台为准，改 yml 不会覆盖已有 runtime.json。

## 运行

前提：JDK 21、Maven 3.9+；stdio 示例上游需要 Node.js（`npx`）或 `uvx`。

```bash
mvn -q package
GATEWAY_ADMIN_TOKEN=my-admin GATEWAY_API_KEY=my-secret java -jar target/mcp-gateway-0.1.0.jar
```

也可以在 Cursor / IntelliJ 里直接运行 `McpGatewayApplication`。

- 控制台：`http://localhost:8080/`（口令 = `GATEWAY_ADMIN_TOKEN`，默认 `change-me`）
- MCP 端点：`http://localhost:8080/mcp`

## 配置

`src/main/resources/application.yml`：

```yaml
gateway:
  admin-token: ${GATEWAY_ADMIN_TOKEN:change-me}
  data-dir: ${GATEWAY_DATA_DIR:${user.home}/.mcp-gateway}
  api-keys:                 # 仅作首次播种；之后在控制台「API 密钥」管理
    - ${GATEWAY_API_KEY:change-me}
  request-timeout: 60s
  upstreams:
    everything:             # 服务名就是工具前缀，不能包含 "__"
      transport: stdio
      command: npx
      args: ["-y", "@modelcontextprotocol/server-everything"]
```

某个上游连不上只会打错误日志，不影响其它上游。

## 限制策略怎么生效

`tools/list` 会按当前 API Key 过滤；`tools/call` 在转发前检查：

1. 上游已启用
2. 密钥未被禁用，且（若配置了）服务名 / 工具名在密钥允许范围内
3. 工具不在该上游的拒绝列表；若上游配置了允许列表，则必须命中
4. 未超过密钥或上游的每分钟次数

规则可写短名（`read_file`）、全名（`filesystem__read_file`）或前缀通配（`filesystem__*`、`read_*`）。

## 测试（示例上游 server-everything）

默认配置已经带了 `everything` 上游，启动后：

```bash
# 未带 key -> 401
curl -i -X POST localhost:8080/mcp -H 'Content-Type: application/json' -d '{}'

# 列出工具
curl -s localhost:8080/mcp -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer change-me' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 调用工具
curl -s localhost:8080/mcp -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer change-me' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"everything__echo","arguments":{"message":"你好"}}}'
# => {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"Echo: 你好"}]}}
```

## 在 Cursor 中使用

编辑 `~/.cursor/mcp.json`（或项目下 `.cursor/mcp.json`）：

```json
{
  "mcpServers": {
    "my-gateway": {
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer change-me" }
    }
  }
}
```

重启或在 Cursor 设置的 MCP 页面刷新，就能看到 `everything__echo` 等工具。控制台「总览」页也可以复制这段配置。

## 代码结构

```
src/main/java/com/example/mcpgateway/
├── McpGatewayApplication.java   启动类
├── GatewayProperties.java       gateway.* 配置绑定（首次播种）
├── RuntimeConfig.java           控制台管理的密钥 / 上游 / 策略
├── ConfigStore.java             读写 runtime.json
├── UpstreamRegistry.java        动态连接上游、聚合工具、路由调用
├── PolicyService.java           工具范围 + 限流
├── CallLogStore.java            最近调用
├── McpEndpoint.java             对外 /mcp 端点（JSON-RPC）
├── ApiKeyFilter.java            客户端 API Key 鉴权
├── AdminAuthFilter.java         控制台口令
├── AdminController.java         /api/admin/*
└── ServerTemplates.java         部署模板
src/main/resources/static/       控制台 UI
```

## 当前限制 / 后续方向

- 对外端点是**无状态** Streamable HTTP：每个 POST 直接返回 JSON，不支持服务端推送（GET SSE 返回 405）、批量请求、进度通知。
  原因：官方 SDK 2.x 暂无 WebFlux 服务端传输（`mcp-spring-webflux` 停在 0.18.x），所以服务端协议层是自己写的一个小 JSON-RPC 处理器。
- 只代理 tools，还没有 resources / prompts。
- 上游工具列表每次 `tools/list` 实时拉取；控制台断开后不会自动重连（点「连接」或重启）。
- 上游调用用的是同步客户端（在 boundedElastic 线程池执行）。
- 调用记录只在内存里，重启清空。
- 之后可换 Spring Security + OAuth2/JWT，加 OpenTelemetry。
