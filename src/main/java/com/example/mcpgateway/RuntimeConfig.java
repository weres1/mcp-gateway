package com.example.mcpgateway;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 控制台管理的运行时配置（持久化到 runtime.json）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuntimeConfig(List<ManagedKey> keys, Map<String, ManagedServer> servers) {

    public RuntimeConfig {
        keys = keys == null ? List.of() : List.copyOf(keys);
        servers = servers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(servers));
    }

    public static RuntimeConfig empty() {
        return new RuntimeConfig(List.of(), Map.of());
    }

    public static RuntimeConfig seed(GatewayProperties props) {
        List<ManagedKey> keys = new ArrayList<>();
        int i = 0;
        for (String secret : props.apiKeys()) {
            if (secret == null || secret.isBlank()) continue;
            i++;
            keys.add(ManagedKey.create("bootstrap-" + i, secret, List.of(), List.of(), null));
        }
        Map<String, ManagedServer> servers = new LinkedHashMap<>();
        props.upstreams().forEach((name, upstream) ->
                servers.put(name, ManagedServer.fromYaml(name, upstream)));
        return new RuntimeConfig(keys, servers);
    }

    public Optional<ManagedKey> keyBySecret(String secret) {
        if (secret == null) return Optional.empty();
        return keys.stream().filter(k -> secret.equals(k.secret())).findFirst();
    }

    public Optional<ManagedKey> keyById(String id) {
        return keys.stream().filter(k -> k.id().equals(id)).findFirst();
    }

    public Optional<ManagedServer> server(String name) {
        return Optional.ofNullable(servers.get(name));
    }

    public RuntimeConfig withServer(ManagedServer server) {
        Map<String, ManagedServer> next = new LinkedHashMap<>(servers);
        next.put(server.name(), server);
        return new RuntimeConfig(keys, next);
    }

    public RuntimeConfig withoutServer(String name) {
        Map<String, ManagedServer> next = new LinkedHashMap<>(servers);
        next.remove(name);
        return new RuntimeConfig(keys, next);
    }

    public RuntimeConfig withKey(ManagedKey key) {
        List<ManagedKey> next = new ArrayList<>();
        boolean replaced = false;
        for (ManagedKey k : keys) {
            if (k.id().equals(key.id())) {
                next.add(key);
                replaced = true;
            } else {
                next.add(k);
            }
        }
        if (!replaced) next.add(key);
        return new RuntimeConfig(next, servers);
    }

    public RuntimeConfig withoutKey(String id) {
        return new RuntimeConfig(keys.stream().filter(k -> !k.id().equals(id)).toList(), servers);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManagedKey(
            String id,
            String name,
            String secret,
            boolean enabled,
            List<String> allowedServers,
            List<String> allowedTools,
            Integer maxCallsPerMinute,
            Instant createdAt) {

        public ManagedKey {
            id = id == null ? UUID.randomUUID().toString() : id;
            name = name == null || name.isBlank() ? "key" : name;
            allowedServers = allowedServers == null ? List.of() : List.copyOf(allowedServers);
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }

        public static ManagedKey create(
                String name,
                String secret,
                List<String> allowedServers,
                List<String> allowedTools,
                Integer maxCallsPerMinute) {
            return new ManagedKey(
                    UUID.randomUUID().toString(),
                    name,
                    secret,
                    true,
                    allowedServers,
                    allowedTools,
                    maxCallsPerMinute,
                    Instant.now());
        }

        public ManagedKey withPolicy(
                String name,
                boolean enabled,
                List<String> allowedServers,
                List<String> allowedTools,
                Integer maxCallsPerMinute) {
            return new ManagedKey(
                    id, name, secret, enabled, allowedServers, allowedTools, maxCallsPerMinute, createdAt);
        }

        @JsonIgnore
        public String maskedSecret() {
            if (secret == null || secret.isEmpty()) return "";
            if (secret.length() <= 6) return "••••";
            return secret.substring(0, Math.min(3, secret.length())) + "••••" + secret.substring(secret.length() - 4);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManagedServer(
            String name,
            GatewayProperties.Transport transport,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            String endpoint,
            Map<String, String> headers,
            boolean enabled,
            ServerPolicy policy) {

        public ManagedServer {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            policy = policy == null ? ServerPolicy.unrestricted() : policy;
        }

        @JsonCreator
        public static ManagedServer json(
                String name,
                GatewayProperties.Transport transport,
                String command,
                List<String> args,
                Map<String, String> env,
                String url,
                String endpoint,
                Map<String, String> headers,
                Boolean enabled,
                ServerPolicy policy) {
            return new ManagedServer(
                    name,
                    transport,
                    command,
                    args,
                    env,
                    url,
                    endpoint,
                    headers,
                    enabled == null || enabled,
                    policy);
        }

        public static ManagedServer fromYaml(String name, GatewayProperties.Upstream u) {
            return new ManagedServer(
                    name,
                    u.transport(),
                    u.command(),
                    u.args(),
                    u.env(),
                    u.url(),
                    u.endpoint(),
                    u.headers(),
                    u.isEnabled(),
                    ServerPolicy.unrestricted());
        }

        public ManagedServer withEnabled(boolean on) {
            return new ManagedServer(name, transport, command, args, env, url, endpoint, headers, on, policy);
        }

        public ManagedServer withPolicy(ServerPolicy next) {
            return new ManagedServer(name, transport, command, args, env, url, endpoint, headers, enabled, next);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerPolicy(List<String> allowedTools, List<String> deniedTools, Integer maxCallsPerMinute) {
        public ServerPolicy {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            deniedTools = deniedTools == null ? List.of() : List.copyOf(deniedTools);
        }

        public static ServerPolicy unrestricted() {
            return new ServerPolicy(List.of(), List.of(), null);
        }
    }

    static boolean namesMatch(Collection<String> rules, String tool, String prefixed) {
        if (rules == null || rules.isEmpty()) return false;
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) continue;
            String r = rule.trim();
            if (r.equals(tool) || r.equals(prefixed)) return true;
            if (r.endsWith("*")) {
                String prefix = r.substring(0, r.length() - 1);
                if (tool.startsWith(prefix) || prefixed.startsWith(prefix)) return true;
            }
        }
        return false;
    }
}
