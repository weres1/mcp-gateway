package com.example.mcpgateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,47}$");
    private static final String MASK = "••••••••";
    private static final SecureRandom RNG = new SecureRandom();

    private final GatewayProperties props;
    private final ConfigStore store;
    private final UpstreamRegistry registry;
    private final CallLogStore callLogs;
    private final int port;

    public AdminController(
            GatewayProperties props,
            ConfigStore store,
            UpstreamRegistry registry,
            CallLogStore callLogs,
            @Value("${server.port:8080}") int port) {
        this.props = props;
        this.store = store;
        this.registry = registry;
        this.callLogs = callLogs;
        this.port = port;
    }

    public record LoginRequest(String token) {}
    public record DeployRequest(
            String name,
            String transport,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            String endpoint,
            Map<String, String> headers,
            Boolean enabled,
            Boolean connect,
            RuntimeConfig.ServerPolicy policy) {}
    public record KeyWriteRequest(
            String name,
            Boolean enabled,
            List<String> allowedServers,
            List<String> allowedTools,
            Integer maxCallsPerMinute) {}
    public record PolicyWriteRequest(
            List<String> allowedTools,
            List<String> deniedTools,
            Integer maxCallsPerMinute) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body) {
        String token = body == null ? null : body.token();
        if (token == null || !token.equals(props.adminToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员口令错误");
        }
        return Map.of("ok", true);
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        RuntimeConfig cfg = store.get();
        Instant hourAgo = Instant.now().minusSeconds(3600);
        int connected = 0, errors = 0, disabled = 0;
        int tools = 0;
        List<Map<String, Object>> health = new ArrayList<>();
        for (var e : cfg.servers().entrySet()) {
            var rt = registry.statusOf(e.getKey());
            switch (rt.status()) {
                case "connected" -> connected++;
                case "error" -> errors++;
                case "disabled" -> disabled++;
                default -> {}
            }
            tools += Math.max(rt.toolCount(), 0);
            health.add(serverView(e.getValue(), rt, false));
        }
        long calls = callLogs.countSince(hourAgo, l -> true);
        long denied = callLogs.countSince(hourAgo, CallLogStore.CallLog::denied);
        long failed = callLogs.countSince(hourAgo, l -> !l.ok() && !l.denied());
        return Map.of(
                "endpoint", "http://localhost:" + port + "/mcp",
                "servers", Map.of(
                        "total", cfg.servers().size(),
                        "connected", connected,
                        "errors", errors,
                        "disabled", disabled),
                "tools", tools,
                "keys", Map.of(
                        "total", cfg.keys().size(),
                        "enabled", cfg.keys().stream().filter(RuntimeConfig.ManagedKey::enabled).count()),
                "calls", Map.of("lastHour", calls, "deniedLastHour", denied, "errorsLastHour", failed),
                "health", health,
                "recent", callLogs.recent().stream().limit(8).toList());
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return ServerTemplates.all();
    }

    @GetMapping("/servers")
    public List<Map<String, Object>> servers() {
        return store.get().servers().values().stream()
                .map(s -> serverView(s, registry.statusOf(s.name()), false))
                .toList();
    }

    @GetMapping("/servers/{name}")
    public Map<String, Object> server(@PathVariable String name) {
        var cfg = store.get().server(name)
                .orElseThrow(() -> new IllegalArgumentException("未知上游: " + name));
        return serverView(cfg, registry.statusOf(name), true);
    }

    @PostMapping("/servers")
    public Mono<Map<String, Object>> deploy(@RequestBody DeployRequest req) {
        return Mono.fromCallable(() -> {
            ManagedDraft draft = validate(req, null);
            if (store.get().servers().containsKey(draft.server().name())) {
                throw new IllegalArgumentException("上游已存在: " + draft.server().name());
            }
            store.update(cfg -> cfg.withServer(draft.server()));
            if (draft.connect()) registry.connect(draft.server().name());
            return serverView(draft.server(), registry.statusOf(draft.server().name()), true);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/servers/{name}")
    public Mono<Map<String, Object>> update(@PathVariable String name, @RequestBody DeployRequest req) {
        return Mono.fromCallable(() -> {
            var existing = store.get().server(name)
                    .orElseThrow(() -> new IllegalArgumentException("未知上游: " + name));
            ManagedDraft draft = validate(req, existing);
            store.update(cfg -> cfg.withServer(draft.server()));
            boolean wasUp = registry.connectedServers().contains(name);
            if (draft.connect() || (wasUp && draft.server().enabled())) {
                registry.connect(name);
            } else if (!draft.server().enabled()) {
                registry.disconnect(name);
            }
            return serverView(store.get().servers().get(name), registry.statusOf(name), true);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/servers/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        if (store.get().server(name).isEmpty()) {
            throw new IllegalArgumentException("未知上游: " + name);
        }
        registry.forget(name);
        store.update(cfg -> cfg.withoutServer(name));
        return Map.of("ok", true);
    }

    @PostMapping("/servers/{name}/connect")
    public Mono<Map<String, Object>> connect(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            var cfg = store.get().server(name)
                    .orElseThrow(() -> new IllegalArgumentException("未知上游: " + name));
            if (!cfg.enabled()) {
                store.update(c -> c.withServer(cfg.withEnabled(true)));
            }
            registry.connect(name);
            return serverView(store.get().servers().get(name), registry.statusOf(name), true);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/servers/{name}/disconnect")
    public Map<String, Object> disconnect(@PathVariable String name) {
        var cfg = store.get().server(name)
                .orElseThrow(() -> new IllegalArgumentException("未知上游: " + name));
        return serverView(cfg, registry.disconnect(name), true);
    }

    @PutMapping("/servers/{name}/policy")
    public Map<String, Object> updatePolicy(@PathVariable String name, @RequestBody PolicyWriteRequest req) {
        var cfg = store.get().server(name)
                .orElseThrow(() -> new IllegalArgumentException("未知上游: " + name));
        var policy = new RuntimeConfig.ServerPolicy(
                emptyToNullList(req.allowedTools()),
                emptyToNullList(req.deniedTools()),
                req.maxCallsPerMinute());
        store.update(c -> c.withServer(cfg.withPolicy(policy)));
        return serverView(store.get().servers().get(name), registry.statusOf(name), true);
    }

    @GetMapping("/keys")
    public List<Map<String, Object>> keys() {
        return store.get().keys().stream().map(this::keyView).toList();
    }

    @PostMapping("/keys")
    public Map<String, Object> createKey(@RequestBody KeyWriteRequest req) {
        String name = req == null || req.name() == null || req.name().isBlank() ? "client" : req.name().trim();
        String secret = "gw_" + HexFormat.of().formatHex(randomBytes(18));
        var key = RuntimeConfig.ManagedKey.create(
                name,
                secret,
                sanitizeList(req == null ? null : req.allowedServers()),
                sanitizeList(req == null ? null : req.allowedTools()),
                req == null ? null : req.maxCallsPerMinute());
        store.update(c -> c.withKey(key));
        Map<String, Object> view = new LinkedHashMap<>(keyView(key));
        view.put("secret", secret);
        view.put("once", true);
        return view;
    }

    @PutMapping("/keys/{id}")
    public Map<String, Object> updateKey(@PathVariable String id, @RequestBody KeyWriteRequest req) {
        var existing = store.get().keyById(id)
                .orElseThrow(() -> new IllegalArgumentException("未知密钥: " + id));
        var next = existing.withPolicy(
                req.name() == null || req.name().isBlank() ? existing.name() : req.name().trim(),
                req.enabled() == null ? existing.enabled() : req.enabled(),
                req.allowedServers() == null ? existing.allowedServers() : sanitizeList(req.allowedServers()),
                req.allowedTools() == null ? existing.allowedTools() : sanitizeList(req.allowedTools()),
                req.maxCallsPerMinute());
        store.update(c -> c.withKey(next));
        return keyView(next);
    }

    @DeleteMapping("/keys/{id}")
    public Map<String, Object> deleteKey(@PathVariable String id) {
        if (store.get().keyById(id).isEmpty()) {
            throw new IllegalArgumentException("未知密钥: " + id);
        }
        store.update(c -> c.withoutKey(id));
        return Map.of("ok", true);
    }

    @GetMapping("/logs")
    public List<CallLogStore.CallLog> logs() {
        return callLogs.recent();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", String.valueOf(e.getReason())));
    }

    private record ManagedDraft(RuntimeConfig.ManagedServer server, boolean connect) {}

    private ManagedDraft validate(DeployRequest req, RuntimeConfig.ManagedServer existing) {
        if (req == null) throw new IllegalArgumentException("缺少请求体");
        String name = existing != null ? existing.name() : (req.name() == null ? "" : req.name().trim());
        if (!NAME.matcher(name).matches() || name.contains(UpstreamRegistry.SEP)) {
            throw new IllegalArgumentException("名称需为字母开头的 [A-Za-z0-9_-]，且不能包含 __");
        }
        GatewayProperties.Transport transport = parseTransport(
                req.transport() != null ? req.transport() : (existing == null ? null : existing.transport().name()));
        if (transport == null) throw new IllegalArgumentException("需要 transport");

        Map<String, String> env = mergeSecrets(existing == null ? Map.of() : existing.env(), req.env());
        Map<String, String> headers = mergeSecrets(existing == null ? Map.of() : existing.headers(), req.headers());
        List<String> args = req.args() != null ? sanitizeList(req.args())
                : (existing == null ? List.of() : existing.args());
        String command = firstNonBlank(req.command(), existing == null ? null : existing.command());
        String url = firstNonBlank(req.url(), existing == null ? null : existing.url());
        String endpoint = req.endpoint() != null ? blankToNull(req.endpoint())
                : (existing == null ? null : existing.endpoint());
        boolean enabled = req.enabled() == null ? (existing == null || existing.enabled()) : req.enabled();
        var policy = req.policy() != null ? req.policy()
                : (existing == null ? RuntimeConfig.ServerPolicy.unrestricted() : existing.policy());

        if (transport == GatewayProperties.Transport.STDIO && (command == null || command.isBlank())) {
            throw new IllegalArgumentException("stdio 上游需要 command");
        }
        if (transport != GatewayProperties.Transport.STDIO && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("HTTP/SSE 上游需要 url");
        }

        var server = new RuntimeConfig.ManagedServer(
                name, transport, command, args, env, url, endpoint, headers, enabled, policy);
        boolean connect = req.connect() == null ? existing == null : req.connect();
        return new ManagedDraft(server, connect && enabled);
    }

    private Map<String, Object> serverView(
            RuntimeConfig.ManagedServer s, UpstreamRegistry.ServerRuntime rt, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("transport", s.transport() == null ? null : s.transport().name());
        m.put("enabled", s.enabled());
        m.put("status", rt.status());
        m.put("error", rt.error());
        m.put("serverName", rt.serverName());
        m.put("serverVersion", rt.serverVersion());
        m.put("toolCount", rt.toolCount());
        m.put("command", s.command());
        m.put("args", s.args());
        m.put("url", s.url());
        m.put("endpoint", s.endpoint());
        m.put("env", maskMap(s.env()));
        m.put("headers", maskMap(s.headers()));
        m.put("policy", s.policy());
        if (detail) m.put("tools", registry.toolsOf(s.name()));
        return m;
    }

    private Map<String, Object> keyView(RuntimeConfig.ManagedKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.id());
        m.put("name", k.name());
        m.put("secret", k.maskedSecret());
        m.put("enabled", k.enabled());
        m.put("allowedServers", k.allowedServers());
        m.put("allowedTools", k.allowedTools());
        m.put("maxCallsPerMinute", k.maxCallsPerMinute());
        m.put("createdAt", k.createdAt());
        return m;
    }

    private static GatewayProperties.Transport parseTransport(String raw) {
        try {
            return GatewayProperties.Transport.from(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的 transport: " + raw);
        }
    }

    private static Map<String, String> mergeSecrets(Map<String, String> old, Map<String, String> incoming) {
        if (incoming == null) return old == null ? Map.of() : old;
        Map<String, String> out = new LinkedHashMap<>();
        incoming.forEach((k, v) -> {
            if (k == null || k.isBlank()) return;
            if (v == null || v.isBlank() || isMasked(v)) {
                if (old != null && old.containsKey(k)) out.put(k, old.get(k));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Map<String, String> maskMap(Map<String, String> src) {
        Map<String, String> out = new LinkedHashMap<>();
        if (src != null) src.forEach((k, v) -> out.put(k, (v == null || v.isEmpty()) ? "" : MASK));
        return out;
    }

    private static boolean isMasked(String v) {
        return MASK.equals(v) || v.chars().allMatch(c -> c == '•' || c == '*');
    }

    private static List<String> sanitizeList(List<String> in) {
        if (in == null) return List.of();
        return in.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    }

    private static List<String> emptyToNullList(List<String> in) {
        return sanitizeList(in);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }
}
