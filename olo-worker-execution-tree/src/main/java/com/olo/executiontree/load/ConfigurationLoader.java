package com.olo.executiontree.load;

import com.olo.executiontree.ExecutionTreeConfig;
import com.olo.executiontree.config.PipelineConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads pipeline configuration in order: Redis → DB → local queue config file → (if queue ends with "-debug") base queue file → default file.
 * If none is available, waits for the configured retry seconds and repeats until a valid config is found.
 * When config is loaded from a local file (queue or default), it is written back to Redis and DB via
 * {@link ConfigSink} so other containers can use it. When config is loaded from Redis or DB, it is
 * normalized (missing node ids get a UUID) and, if a sink is set, the normalized config is written back
 * to Redis and DB so the stored copy always has unique node ids.
 */
public final class ConfigurationLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationLoader.class);

    private static final String DEFAULT_CONFIG_FILE = "default.json";
    private static final String DEBUG_QUEUE_SUFFIX = "-debug";

    private final ConfigSource configSource;
    private final ConfigSink configSink;
    private final Path configDir;
    private final int retryWaitSeconds;
    private final ConfigKeyBuilder keyBuilder;

    /**
     * @param configSource     source for Redis and DB (may return empty); must not be null
     * @param configDir        directory for local config files (e.g. config/); queue file = {@code queueName}.json
     * @param retryWaitSeconds seconds to wait before retrying the load cycle when no config is found
     * @param configKeyPrefix  prefix for Redis key (e.g. olo:engine:config); null = default
     */
    public ConfigurationLoader(
            ConfigSource configSource,
            Path configDir,
            int retryWaitSeconds,
            String configKeyPrefix) {
        this(configSource, null, configDir, retryWaitSeconds, configKeyPrefix);
    }

    /**
     * @param configSource     source for Redis and DB (may return empty); must not be null
     * @param configSink       when non-null, config loaded from local file is written to Redis and DB for other containers
     * @param configDir        directory for local config files (e.g. config/); queue file = {@code queueName}.json
     * @param retryWaitSeconds seconds to wait before retrying the load cycle when no config is found
     * @param configKeyPrefix  prefix for Redis key (e.g. olo:engine:config); null = default
     */
    public ConfigurationLoader(
            ConfigSource configSource,
            ConfigSink configSink,
            Path configDir,
            int retryWaitSeconds,
            String configKeyPrefix) {
        this.configSource = configSource;
        this.configSink = configSink;
        this.configDir = configDir;
        this.retryWaitSeconds = Math.max(0, retryWaitSeconds);
        this.keyBuilder = new ConfigKeyBuilder(configKeyPrefix);
    }

    /**
     * Loads configuration for the given tenant, queue and version. Tries Redis → DB → queueName.json → (if queue ends with "-debug") baseQueue.json → default.json.
     * If none available, waits retryWaitSeconds and retries until a valid configuration is found.
     * Redis key uses tenant-scoped prefix (olo:&lt;tenantId&gt;:...); DB lookup uses tenant_id.
     *
     * @param tenantKey tenant id (for Redis key prefix and DB)
     * @param queueName task queue name (e.g. olo-chat-queue-oolama or olo-chat-queue-oolama-debug)
     * @param version   config version (e.g. 1.0)
     * @return valid pipeline configuration (never null)
     */
    public PipelineConfiguration loadConfiguration(String tenantKey, String queueName, String version) {
        AtomicReference<PipelineConfiguration> ref = new AtomicReference<>();
        while (ref.get() == null) {
            Optional<PipelineConfiguration> cfg = tryLoadOnce(tenantKey, queueName, version);
            if (cfg.isPresent()) {
                ref.set(cfg.get());
                break;
            }
            if (retryWaitSeconds > 0) {
                log.warn("No pipeline configuration found for tenant={} queue={} version={}; retrying in {}s",
                        tenantKey, queueName, version, retryWaitSeconds);
                try {
                    Thread.sleep(retryWaitSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for configuration", e);
                }
            } else {
                throw new IllegalStateException(
                        "No pipeline configuration found for tenant=" + tenantKey + " queue=" + queueName + " version=" + version
                                + " (Redis, DB, local file, default). Configure at least one source or set retry.");
            }
        }
        return ref.get();
    }

    /**
     * One attempt: Redis → DB → queueName.json → (if -debug queue) baseQueue.json → default.json. Returns empty if none found or parse failed.
     */
    public Optional<PipelineConfiguration> tryLoadOnce(String tenantKey, String queueName, String version) {
        String redisKey = keyBuilder.redisKey(queueName, version);

        Optional<String> json = configSource.getFromCache(redisKey);
        if (json.isPresent()) {
            Optional<PipelineConfiguration> cfg = parseConfig(json.get(), "Redis:" + redisKey);
            if (cfg.isPresent()) {
                PipelineConfiguration normalized = ExecutionTreeConfig.ensureUniqueNodeIds(cfg.get());
                log.info("Pipeline configuration loaded from Redis key={} for queue={} version={}", redisKey, queueName, version);
                if (configSink != null) {
                    persistConfig(tenantKey, queueName, version, redisKey, normalized, "Redis");
                }
                return Optional.of(normalized);
            }
        }

        json = configSource.getFromDb(tenantKey, queueName, version);
        if (json.isPresent()) {
            Optional<PipelineConfiguration> cfg = parseConfig(json.get(), "DB:" + queueName + ":" + version);
            if (cfg.isPresent()) {
                PipelineConfiguration normalized = ExecutionTreeConfig.ensureUniqueNodeIds(cfg.get());
                log.info("Pipeline configuration loaded from DB tenant={} queue={} version={}", tenantKey, queueName, version);
                if (configSink != null) {
                    persistConfig(tenantKey, queueName, version, redisKey, normalized, "DB");
                }
                return Optional.of(normalized);
            }
        }

        Optional<PipelineConfiguration> cfg = tryLoadFromLocalFile(tenantKey, queueName + ".json", queueName, version, redisKey, "");
        if (cfg.isPresent()) return cfg;

        if (queueName.endsWith(DEBUG_QUEUE_SUFFIX)) {
            String baseQueue = queueName.substring(0, queueName.length() - DEBUG_QUEUE_SUFFIX.length());
            cfg = tryLoadFromLocalFile(tenantKey, baseQueue + ".json", queueName, version, redisKey, " (base queue file for -debug)");
            if (cfg.isPresent()) return cfg;
        }

        cfg = tryLoadFromLocalFile(tenantKey, DEFAULT_CONFIG_FILE, queueName, version, redisKey, " (default)");
        if (cfg.isPresent()) return cfg;

        return Optional.empty();
    }

    /**
     * Tries to load config from a single local file. If found and valid, logs and optionally persists to Redis/DB.
     */
    private Optional<PipelineConfiguration> tryLoadFromLocalFile(String tenantKey, String fileName, String queueName, String version, String redisKey, String logSuffix) {
        Optional<String> json = readLocalFile(fileName);
        if (json.isEmpty()) return Optional.empty();
        Optional<PipelineConfiguration> cfg = parseConfig(json.get(), "file:" + fileName);
        if (cfg.isEmpty()) return Optional.empty();
        PipelineConfiguration normalized = ExecutionTreeConfig.ensureUniqueNodeIds(cfg.get());
        Path filePath = configDir != null ? configDir.resolve(fileName) : Path.of(fileName);
        log.info("Pipeline configuration loaded from file: {}{} for tenant={} queue={} version={}", filePath, logSuffix, tenantKey, queueName, version);
        if (configSink != null) {
            persistConfig(tenantKey, queueName, version, redisKey, normalized, "file:" + filePath);
        }
        return Optional.of(normalized);
    }

    private void persistConfig(String tenantKey, String queueName, String version, String redisKey, PipelineConfiguration config, String source) {
        try {
            String json = ExecutionTreeConfig.toJson(config);
            configSink.putInCache(redisKey, json);
            configSink.putInDb(tenantKey, queueName, version, json);
            log.info("Pipeline configuration (with unique node ids) persisted to Redis key={} and DB for tenant={} queue={} version={} (source: {})", redisKey, tenantKey, queueName, version, source);
        } catch (Exception e) {
            log.warn("Failed to persist config to Redis/DB for tenant={} queue={} version={}: {}", tenantKey, queueName, version, e.getMessage());
        }
    }

    private Optional<PipelineConfiguration> parseConfig(String json, String source) {
        try {
            PipelineConfiguration config = ExecutionTreeConfig.fromJson(json);
            return Optional.of(config);
        } catch (Exception e) {
            log.warn("Failed to parse pipeline configuration from {}: {}", source, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> readLocalFile(String fileName) {
        if (configDir == null) {
            return Optional.empty();
        }
        Path file = configDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            log.warn("Failed to read config file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
