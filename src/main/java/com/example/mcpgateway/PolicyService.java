package com.example.mcpgateway;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 按上游策略 + API Key 范围过滤工具，并做每分钟限流。 */
@Component
public class PolicyService {

    private final ConfigStore store;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> windows = new ConcurrentHashMap<>();

    public PolicyService(ConfigStore store) {
        this.store = store;
    }

    public boolean isToolAllowed(RuntimeConfig.ManagedKey key, String prefixedName) {
        try {
            authorize(key, prefixedName);
            return true;
        } catch (PolicyDeniedException e) {
            return false;
        }
    }

    public List<io.modelcontextprotocol.spec.McpSchema.Tool> filterTools(
            RuntimeConfig.ManagedKey key,
            List<io.modelcontextprotocol.spec.McpSchema.Tool> tools) {
        return tools.stream().filter(t -> isToolAllowed(key, t.name())).toList();
    }

    public void authorize(RuntimeConfig.ManagedKey key, String prefixedName) {
        int i = prefixedName.indexOf(UpstreamRegistry.SEP);
        if (i <= 0) throw new PolicyDeniedException("工具名缺少前缀: " + prefixedName);
        String server = prefixedName.substring(0, i);
        String tool = prefixedName.substring(i + UpstreamRegistry.SEP.length());

        RuntimeConfig.ManagedServer cfg = store.get().servers().get(server);
        if (cfg == null || !cfg.enabled()) {
            throw new PolicyDeniedException("上游不可用: " + server);
        }

        if (key != null) {
            if (!key.enabled()) throw new PolicyDeniedException("API Key 已禁用");
            if (!key.allowedServers().isEmpty() && !key.allowedServers().contains(server)) {
                throw new PolicyDeniedException("此密钥无权访问上游 " + server);
            }
            if (!key.allowedTools().isEmpty()
                    && !RuntimeConfig.namesMatch(key.allowedTools(), tool, prefixedName)) {
                throw new PolicyDeniedException("此密钥无权调用 " + prefixedName);
            }
        }

        RuntimeConfig.ServerPolicy policy = cfg.policy();
        if (RuntimeConfig.namesMatch(policy.deniedTools(), tool, prefixedName)) {
            throw new PolicyDeniedException("工具已被服务器策略拒绝: " + tool);
        }
        if (!policy.allowedTools().isEmpty()
                && !RuntimeConfig.namesMatch(policy.allowedTools(), tool, prefixedName)) {
            throw new PolicyDeniedException("工具不在服务器允许列表: " + tool);
        }
    }

    public void checkRate(RuntimeConfig.ManagedKey key, String prefixedName) {
        int i = prefixedName.indexOf(UpstreamRegistry.SEP);
        String server = i > 0 ? prefixedName.substring(0, i) : prefixedName;
        if (key != null && key.maxCallsPerMinute() != null) {
            if (!tryAcquire("key:" + key.id(), key.maxCallsPerMinute())) {
                throw new PolicyDeniedException("API Key「" + key.name() + "」超过每分钟 "
                        + key.maxCallsPerMinute() + " 次限制");
            }
        }
        store.get().server(server).map(RuntimeConfig.ManagedServer::policy).ifPresent(p -> {
            if (p.maxCallsPerMinute() != null && !tryAcquire("server:" + server, p.maxCallsPerMinute())) {
                throw new PolicyDeniedException("上游 " + server + " 超过每分钟 "
                        + p.maxCallsPerMinute() + " 次限制");
            }
        });
    }

    private boolean tryAcquire(String bucket, int maxPerMinute) {
        if (maxPerMinute <= 0) return false;
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000;
        ConcurrentLinkedDeque<Long> q = windows.computeIfAbsent(bucket, k -> new ConcurrentLinkedDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
            if (q.size() >= maxPerMinute) return false;
            q.addLast(now);
            return true;
        }
    }

    public static class PolicyDeniedException extends RuntimeException {
        public PolicyDeniedException(String message) {
            super(message);
        }
    }
}
