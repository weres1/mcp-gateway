package com.example.mcpgateway;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** application.yml 中 gateway.* 配置（启动种子；运行时以 data-dir/runtime.json 为准）。 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        List<String> apiKeys,
        Duration requestTimeout,
        Map<String, Upstream> upstreams,
        String adminToken,
        String dataDir) {

    public GatewayProperties {
        apiKeys = apiKeys == null ? List.of() : apiKeys;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
        upstreams = upstreams == null ? Map.of() : upstreams;
        adminToken = (adminToken == null || adminToken.isBlank()) ? "change-me" : adminToken;
        dataDir = (dataDir == null || dataDir.isBlank())
                ? System.getProperty("user.home") + "/.mcp-gateway"
                : dataDir;
    }

    public enum Transport {
        STDIO, STREAMABLE_HTTP, SSE;

        @JsonCreator
        public static Transport from(String value) {
            if (value == null || value.isBlank()) return null;
            return Transport.valueOf(value.trim().toUpperCase().replace('-', '_'));
        }
    }

    /**
     * 一个上游 MCP Server。
     * stdio: command/args/env；streamable_http / sse: url(+ endpoint)/headers。
     */
    public record Upstream(
            Transport transport,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            String endpoint,
            Map<String, String> headers,
            Boolean enabled) {

        public boolean isEnabled() { return enabled == null || enabled; }
    }
}
