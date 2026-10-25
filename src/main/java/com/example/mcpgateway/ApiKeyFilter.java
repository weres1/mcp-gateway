package com.example.mcpgateway;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** /mcp 鉴权：Authorization: Bearer <key> 或 X-API-Key <key>。无密钥 = 不鉴权。 */
@Component
public class ApiKeyFilter implements WebFilter {

    public static final String ATTR_KEY = "gateway.apiKey";

    private final ConfigStore store;

    public ApiKeyFilter(ConfigStore store) {
        this.store = store;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, WebFilterChain chain) {
        if (!ex.getRequest().getPath().value().startsWith("/mcp")) {
            return chain.filter(ex);
        }
        var keys = store.get().keys();
        if (keys.isEmpty()) return chain.filter(ex);

        var h = ex.getRequest().getHeaders();
        String secret = h.getFirst("X-API-Key");
        String auth = h.getFirst(HttpHeaders.AUTHORIZATION);
        if (secret == null && auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            secret = auth.substring(7).trim();
        }
        var matched = store.get().keyBySecret(secret);
        if (matched.isPresent() && matched.get().enabled()) {
            ex.getAttributes().put(ATTR_KEY, matched.get());
            return chain.filter(ex);
        }
        ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return ex.getResponse().setComplete();
    }
}
