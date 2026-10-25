package com.example.mcpgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.UnaryOperator;

/** 读写 ~/.mcp-gateway/runtime.json，作为控制台的配置源。 */
@Component
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final GatewayProperties props;
    private final ObjectMapper json;
    private final Path file;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile RuntimeConfig snapshot = RuntimeConfig.empty();

    public ConfigStore(GatewayProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.file = Path.of(props.dataDir()).resolve("runtime.json");
    }

    @PostConstruct
    public void load() throws IOException {
        Files.createDirectories(file.getParent());
        lock.writeLock().lock();
        try {
            if (Files.exists(file)) {
                snapshot = json.readValue(file.toFile(), RuntimeConfig.class);
                if (snapshot == null) snapshot = RuntimeConfig.empty();
                log.info("已加载运行时配置 {}（{} 个上游, {} 把密钥）",
                        file, snapshot.servers().size(), snapshot.keys().size());
            } else {
                snapshot = RuntimeConfig.seed(props);
                persistUnlocked(snapshot);
                log.info("已用 application.yml 初始化运行时配置 -> {}", file);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public RuntimeConfig get() {
        return snapshot;
    }

    public RuntimeConfig update(UnaryOperator<RuntimeConfig> mutator) {
        lock.writeLock().lock();
        try {
            RuntimeConfig next = mutator.apply(snapshot);
            persistUnlocked(next);
            snapshot = next;
            return next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void persistUnlocked(RuntimeConfig cfg) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), cfg);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入运行时配置失败: " + e.getMessage(), e);
        }
    }
}
