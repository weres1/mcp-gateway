package com.example.mcpgateway;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 最近工具调用环形缓冲，供控制台活动页使用。 */
@Component
public class CallLogStore {

    public record CallLog(
            Instant at,
            String tool,
            String server,
            String keyName,
            boolean ok,
            boolean denied,
            String error,
            long durationMs) {}

    private static final int MAX = 300;
    private final ConcurrentLinkedDeque<CallLog> logs = new ConcurrentLinkedDeque<>();

    public void add(CallLog log) {
        logs.addFirst(log);
        while (logs.size() > MAX) logs.pollLast();
    }

    public List<CallLog> recent() {
        return new ArrayList<>(logs);
    }

    public long countSince(Instant cutoff, java.util.function.Predicate<CallLog> pred) {
        return logs.stream().filter(l -> !l.at().isBefore(cutoff) && pred.test(l)).count();
    }
}
