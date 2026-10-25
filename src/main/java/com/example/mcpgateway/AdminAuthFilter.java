package com.example.mcpgateway;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** /api/admin/** 需要管理员口令（X-Admin-Token 或 Bearer）。登录接口除外。 */
@Component
@Order(-10)
public class AdminAuthFilter implements WebFilter {

    private final GatewayProperties props;

    public AdminAuthFilter(GatewayProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, WebFilterChain chain) {
        String path = ex.getRequest().getPath().value();
        if (!path.startsWith("/api/admin")) return chain.filter(ex);
        if ("/api/admin/login".equals(path) && ex.getRequest().getMethod() == HttpMethod.POST) {
            return chain.filter(ex);
        }
        var h = ex.getRequest().getHeaders();
        String token = h.getFirst("X-Admin-Token");
        String auth = h.getFirst(HttpHeaders.AUTHORIZATION);
        if (token == null && auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = auth.substring(7).trim();
        }
        if (token != null && token.equals(props.adminToken())) return chain.filter(ex);

        ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        ex.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"需要管理员口令\"}".getBytes(StandardCharsets.UTF_8);
        return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(body)));
    }
}
