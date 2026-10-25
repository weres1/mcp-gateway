package com.example.mcpgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Map;

/**
 * 网关对外的 MCP 端点（Streamable HTTP，无状态模式：每个 POST 直接返回 JSON 响应）。
 * 支持 initialize / ping / tools/list / tools/call，以及忽略通知。
 */
@RestController
@RequestMapping("/mcp")
public class McpEndpoint {

    private static final Logger log = LoggerFactory.getLogger("tool-call");
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final UpstreamRegistry registry;
    private final PolicyService policy;
    private final CallLogStore callLogs;
    private final ObjectMapper json;

    public McpEndpoint(
            UpstreamRegistry registry,
            PolicyService policy,
            CallLogStore callLogs,
            ObjectMapper json) {
        this.registry = registry;
        this.policy = policy;
        this.callLogs = callLogs;
        this.json = json;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> handle(@RequestBody JsonNode msg, ServerWebExchange ex) {
        if (msg.isArray()) {
            return Mono.just(ResponseEntity.badRequest().body(error(null, -32600, "不支持批量请求")));
        }
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText("");
        if (id == null || id.isNull()) {
            return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).build());
        }
        RuntimeConfig.ManagedKey key = (RuntimeConfig.ManagedKey) ex.getAttribute(ApiKeyFilter.ATTR_KEY);
        return Mono.fromCallable(() -> dispatch(method, msg.path("params"), key))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.ok((Object) success(id, result)))
                .onErrorResume(e -> {
                    int code = e instanceof PolicyService.PolicyDeniedException ? -32001 : -32603;
                    return Mono.just(ResponseEntity.ok(error(id, code, e.getMessage())));
                });
    }

    @GetMapping
    public ResponseEntity<Void> sse() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    private Object dispatch(String method, JsonNode params, RuntimeConfig.ManagedKey key) {
        return switch (method) {
            case "initialize" -> Map.of(
                    "protocolVersion", params.path("protocolVersion").asText(PROTOCOL_VERSION),
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "mcp-gateway", "version", "0.1.0"));
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", policy.filterTools(key, registry.listTools()));
            case "tools/call" -> callTool(params, key);
            default -> throw new UnsupportedOperationException("不支持的方法: " + method);
        };
    }

    @SuppressWarnings("unchecked")
    private Object callTool(JsonNode params, RuntimeConfig.ManagedKey key) {
        String name = params.path("name").asText();
        Map<String, Object> args = json.convertValue(params.path("arguments"), Map.class);
        String server = serverOf(name);
        String keyName = key == null ? "(open)" : key.name();
        long start = System.currentTimeMillis();
        try {
            policy.authorize(key, name);
            policy.checkRate(key, name);
            var result = registry.callTool(name, args);
            long ms = System.currentTimeMillis() - start;
            boolean err = Boolean.TRUE.equals(result.isError());
            log.info("tool={} ok isError={} {}ms args={}", name, err, ms, args);
            callLogs.add(new CallLogStore.CallLog(
                    Instant.now(), name, server, keyName, !err, false, null, ms));
            return result;
        } catch (PolicyService.PolicyDeniedException e) {
            long ms = System.currentTimeMillis() - start;
            log.warn("tool={} DENIED {}ms error={}", name, ms, e.getMessage());
            callLogs.add(new CallLogStore.CallLog(
                    Instant.now(), name, server, keyName, false, true, e.getMessage(), ms));
            throw e;
        } catch (RuntimeException e) {
            long ms = System.currentTimeMillis() - start;
            log.warn("tool={} FAILED {}ms error={}", name, ms, e.toString());
            callLogs.add(new CallLogStore.CallLog(
                    Instant.now(), name, server, keyName, false, false, e.getMessage(), ms));
            throw e;
        }
    }

    private static String serverOf(String prefixed) {
        int i = prefixed.indexOf(UpstreamRegistry.SEP);
        return i > 0 ? prefixed.substring(0, i) : prefixed;
    }

    private ObjectNode success(JsonNode id, Object result) {
        ObjectNode n = json.createObjectNode().put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", json.valueToTree(result));
        return n;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode n = json.createObjectNode().put("jsonrpc", "2.0");
        n.set("id", id);
        n.putObject("error").put("code", code).put("message", String.valueOf(message));
        return n;
    }
}
