package com.example.mcpgateway;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 管理上游 MCP 连接：动态部署/连接/断开，聚合工具并路由 tools/call。 */
@Component
public class UpstreamRegistry {

    public static final String SEP = "__";
    private static final Logger log = LoggerFactory.getLogger(UpstreamRegistry.class);

    public record ServerRuntime(
            String status,
            String error,
            String serverName,
            String serverVersion,
            int toolCount) {
        public static ServerRuntime disconnected() {
            return new ServerRuntime("disconnected", null, null, null, 0);
        }
        public static ServerRuntime disabled() {
            return new ServerRuntime("disabled", null, null, null, 0);
        }
        public static ServerRuntime connecting() {
            return new ServerRuntime("connecting", null, null, null, 0);
        }
        public static ServerRuntime error(String message) {
            return new ServerRuntime("error", message, null, null, 0);
        }
        public static ServerRuntime connected(McpSchema.Implementation info, int tools) {
            return new ServerRuntime(
                    "connected",
                    null,
                    info == null ? null : info.name(),
                    info == null ? null : info.version(),
                    tools);
        }
    }

    public record ToolView(String name, String title, String description) {}

    private final GatewayProperties props;
    private final ConfigStore store;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, ServerRuntime> states = new ConcurrentHashMap<>();

    public UpstreamRegistry(GatewayProperties props, ConfigStore store) {
        this.props = props;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connectAll() {
        store.get().servers().forEach((name, cfg) -> {
            if (!cfg.enabled()) {
                states.put(name, ServerRuntime.disabled());
                return;
            }
            connect(name);
        });
    }

    public ServerRuntime connect(String name) {
        RuntimeConfig.ManagedServer cfg = store.get().server(name)
                .orElseThrow(() -> new IllegalArgumentException("未知上游: " + name));
        if (name.contains(SEP)) {
            ServerRuntime err = ServerRuntime.error("上游名称不能包含 '" + SEP + "'");
            states.put(name, err);
            return err;
        }
        if (!cfg.enabled()) {
            disconnectQuiet(name);
            ServerRuntime disabled = ServerRuntime.disabled();
            states.put(name, disabled);
            return disabled;
        }
        disconnectQuiet(name);
        states.put(name, ServerRuntime.connecting());
        try {
            McpSyncClient client = McpClient.sync(buildTransport(cfg))
                    .requestTimeout(props.requestTimeout())
                    .clientInfo(new McpSchema.Implementation("mcp-gateway", "0.1.0"))
                    .build();
            client.initialize();
            clients.put(name, client);
            int tools = countTools(client);
            ServerRuntime ok = ServerRuntime.connected(client.getServerInfo(), tools);
            states.put(name, ok);
            log.info("已连接上游 [{}] ({}) -> {}", name, cfg.transport(), client.getServerInfo());
            return ok;
        } catch (Exception e) {
            ServerRuntime err = ServerRuntime.error(rootMessage(e));
            states.put(name, err);
            log.error("连接上游 [{}] 失败: {}", name, e.toString());
            return err;
        }
    }

    public ServerRuntime disconnect(String name) {
        disconnectQuiet(name);
        RuntimeConfig.ManagedServer cfg = store.get().servers().get(name);
        ServerRuntime next = cfg != null && !cfg.enabled()
                ? ServerRuntime.disabled()
                : ServerRuntime.disconnected();
        states.put(name, next);
        return next;
    }

    public void forget(String name) {
        disconnectQuiet(name);
        states.remove(name);
    }

    public ServerRuntime statusOf(String name) {
        return states.getOrDefault(name, ServerRuntime.disconnected());
    }

    /** 聚合所有上游工具，名称改写为 server__tool。单个上游失败不影响其它。 */
    public List<McpSchema.Tool> listTools() {
        List<McpSchema.Tool> all = new ArrayList<>();
        clients.forEach((server, client) -> {
            try {
                for (var t : listRawTools(client)) {
                    all.add(new McpSchema.Tool(
                            server + SEP + t.name(), t.title(), t.description(),
                            t.inputSchema(), t.outputSchema(), t.annotations(), t.meta(), t.icons()));
                }
            } catch (Exception e) {
                log.warn("获取上游 [{}] 工具列表失败: {}", server, e.toString());
            }
        });
        return all;
    }

    public List<ToolView> toolsOf(String server) {
        McpSyncClient client = clients.get(server);
        if (client == null) return List.of();
        try {
            return listRawTools(client).stream()
                    .map(t -> new ToolView(t.name(), t.title(), t.description()))
                    .toList();
        } catch (Exception e) {
            log.warn("获取上游 [{}] 工具列表失败: {}", server, e.toString());
            return List.of();
        }
    }

    /** 按前缀路由到对应上游。 */
    public McpSchema.CallToolResult callTool(String prefixedName, Map<String, Object> args) {
        int i = prefixedName.indexOf(SEP);
        if (i <= 0) throw new IllegalArgumentException("工具名缺少前缀: " + prefixedName);
        String server = prefixedName.substring(0, i);
        String tool = prefixedName.substring(i + SEP.length());
        McpSyncClient client = clients.get(server);
        if (client == null) throw new IllegalArgumentException("未知或未连接的上游: " + server);
        return client.callTool(McpSchema.CallToolRequest.builder()
                .name(tool).arguments(args == null ? Map.of() : args).build());
    }

    public Set<String> connectedServers() {
        return clients.keySet();
    }

    @PreDestroy
    public void closeAll() {
        clients.keySet().forEach(this::disconnectQuiet);
    }

    private void disconnectQuiet(String name) {
        McpSyncClient client = clients.remove(name);
        if (client == null) return;
        try {
            client.closeGracefully();
        } catch (Exception ignored) {
        }
    }

    private int countTools(McpSyncClient client) {
        try {
            return listRawTools(client).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private List<McpSchema.Tool> listRawTools(McpSyncClient client) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        String cursor = null;
        do {
            var res = client.listTools(cursor);
            tools.addAll(res.tools());
            cursor = res.nextCursor();
        } while (cursor != null);
        return tools;
    }

    private McpClientTransport buildTransport(RuntimeConfig.ManagedServer cfg) {
        var mapper = McpJsonDefaults.getMapper();
        var headers = cfg.headers() == null ? Map.<String, String>of() : cfg.headers();
        HttpRequest.Builder req = HttpRequest.newBuilder();
        headers.forEach(req::header);
        return switch (cfg.transport()) {
            case STDIO -> {
                if (cfg.command() == null || cfg.command().isBlank()) {
                    throw new IllegalArgumentException("stdio 上游缺少 command");
                }
                var params = ServerParameters.builder(cfg.command())
                        .args(cfg.args() == null ? List.of() : cfg.args())
                        .env(cfg.env() == null ? Map.of() : cfg.env())
                        .build();
                yield new StdioClientTransport(params, mapper);
            }
            case STREAMABLE_HTTP -> {
                if (cfg.url() == null || cfg.url().isBlank()) {
                    throw new IllegalArgumentException("HTTP 上游缺少 url");
                }
                var b = HttpClientStreamableHttpTransport.builder(cfg.url()).requestBuilder(req);
                if (cfg.endpoint() != null) b.endpoint(cfg.endpoint());
                yield b.build();
            }
            case SSE -> {
                if (cfg.url() == null || cfg.url().isBlank()) {
                    throw new IllegalArgumentException("SSE 上游缺少 url");
                }
                var b = HttpClientSseClientTransport.builder(cfg.url()).requestBuilder(req);
                if (cfg.endpoint() != null) b.sseEndpoint(cfg.endpoint());
                yield b.build();
            }
        };
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? e.toString() : msg;
    }
}
