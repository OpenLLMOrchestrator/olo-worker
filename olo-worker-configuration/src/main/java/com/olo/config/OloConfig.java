package com.olo.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuration loaded from environment variables for the OLO Temporal worker.
 * <p>
 * Queue names: OLO_QUEUE (comma-separated). If OLO_IS_DEBUG_ENABLED is true,
 * each queue also gets a -debug variant (e.g. olo-chat-queue-oolama-debug, olo-rag-queue-openai-debug).
 * <p>
 * Cache: OLO_CACHE_HOST, OLO_CACHE_PORT. DB: OLO_DB_HOST, OLO_DB_PORT.
 */
public final class OloConfig {

    private static final String ENV_QUEUE = "OLO_QUEUE";
    private static final String ENV_IS_DEBUG_ENABLED = "OLO_IS_DEBUG_ENABLED";
    private static final String DEBUG_QUEUE_SUFFIX = "-debug";
    private static final String ENV_CACHE_HOST = "OLO_CACHE_HOST";
    private static final String ENV_CACHE_PORT = "OLO_CACHE_PORT";
    private static final String ENV_DB_HOST = "OLO_DB_HOST";
    private static final String ENV_DB_PORT = "OLO_DB_PORT";
    private static final String ENV_MAX_LOCAL_MESSAGE_SIZE = "OLO_MAX_LOCAL_MESSAGE_SIZE";
    private static final String ENV_SESSION_DATA = "OLO_SESSION_DATA";
    private static final String ENV_CONFIG_DIR = "OLO_CONFIG_DIR";
    private static final String ENV_CONFIG_VERSION = "OLO_CONFIG_VERSION";
    private static final String ENV_CONFIG_RETRY_WAIT_SECONDS = "OLO_CONFIG_RETRY_WAIT_SECONDS";
    private static final String ENV_CONFIG_KEY_PREFIX = "OLO_CONFIG_KEY_PREFIX";

    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String DEFAULT_CONFIG_VERSION = "1.0";
    private static final int DEFAULT_CONFIG_RETRY_WAIT_SECONDS = 30;
    private static final String DEFAULT_CONFIG_KEY_PREFIX = "olo:kernel:config";
    private static final String DEFAULT_SESSION_DATA_PREFIX = "olo:kernel:sessions:";
    private static final int DEFAULT_MAX_LOCAL_MESSAGE_SIZE = 50;

    private final List<String> taskQueues;
    private final boolean debugQueueEnabled;
    private final String cacheHost;
    private final int cachePort;
    private final String dbHost;
    private final int dbPort;
    private final int maxLocalMessageSize;
    private final String sessionDataPrefix;
    private final String pipelineConfigDir;
    private final String pipelineConfigVersion;
    private final int pipelineConfigRetryWaitSeconds;
    private final String pipelineConfigKeyPrefix;

    private OloConfig(Builder b) {
        this.taskQueues = Collections.unmodifiableList(new ArrayList<>(b.taskQueues));
        this.debugQueueEnabled = b.debugQueueEnabled;
        this.cacheHost = b.cacheHost;
        this.cachePort = b.cachePort;
        this.dbHost = b.dbHost;
        this.dbPort = b.dbPort;
        this.maxLocalMessageSize = b.maxLocalMessageSize;
        this.sessionDataPrefix = b.sessionDataPrefix;
        this.pipelineConfigDir = b.pipelineConfigDir;
        this.pipelineConfigVersion = b.pipelineConfigVersion;
        this.pipelineConfigRetryWaitSeconds = b.pipelineConfigRetryWaitSeconds;
        this.pipelineConfigKeyPrefix = b.pipelineConfigKeyPrefix;
    }

    /**
     * All task queue names this worker should poll: base queues from OLO_QUEUE,
     * and if OLO_IS_DEBUG_ENABLED is true, also each base queue with "-debug" suffix.
     */
    public List<String> getTaskQueues() {
        return taskQueues;
    }

    public boolean isDebugQueueEnabled() {
        return debugQueueEnabled;
    }

    public String getCacheHost() {
        return cacheHost;
    }

    public int getCachePort() {
        return cachePort;
    }

    public String getDbHost() {
        return dbHost;
    }

    public int getDbPort() {
        return dbPort;
    }

    /**
     * Max size (characters) for inline LOCAL string values. Larger values should be stored in cache (e.g. Redis) and the key shared. Default 50.
     */
    public int getMaxLocalMessageSize() {
        return maxLocalMessageSize;
    }

    /**
     * Prefix for session keys (e.g. Redis). Default {@code olo:kernel:sessions:}.
     * Use with {@link #getSessionUserInputKey(String)} to store workflow input per transaction.
     */
    public String getSessionDataPrefix() {
        return sessionDataPrefix;
    }

    /**
     * Session key for storing workflow user input: {@code <sessionDataPrefix><transactionId>:USERINPUT}.
     * Example: {@code olo:kernel:sessions:8huqpd42mizzgjOhJEH9C:USERINPUT}.
     */
    public String getSessionUserInputKey(String transactionId) {
        return sessionDataPrefix + (transactionId != null ? transactionId : "") + ":USERINPUT";
    }

    /** Directory for pipeline config files (e.g. config/). Default {@code config}. */
    public String getPipelineConfigDir() {
        return pipelineConfigDir;
    }

    /** Pipeline config version (e.g. 1.0). Default {@code 1.0}. */
    public String getPipelineConfigVersion() {
        return pipelineConfigVersion;
    }

    /** Seconds to wait before retrying load when no config is found. Default 30. */
    public int getPipelineConfigRetryWaitSeconds() {
        return pipelineConfigRetryWaitSeconds;
    }

    /** Redis key prefix for pipeline config (e.g. olo:kernel:config). */
    public String getPipelineConfigKeyPrefix() {
        return pipelineConfigKeyPrefix;
    }

    public static OloConfig fromEnvironment() {
        String queueEnv = System.getenv(ENV_QUEUE);
        List<String> baseQueues = parseCommaSeparated(queueEnv);
        if (baseQueues.isEmpty()) {
            baseQueues = List.of("olo-chat-queue-oolama", "olo-rag-queue-openai");
        }

        boolean isDebug = parseBoolean(System.getenv(ENV_IS_DEBUG_ENABLED), true);
        List<String> allQueues = new ArrayList<>(baseQueues);
        if (isDebug) {
            for (String q : baseQueues) {
                allQueues.add(q + DEBUG_QUEUE_SUFFIX);
            }
        }

        return builder()
                .taskQueues(allQueues)
                .debugQueueEnabled(isDebug)
                .cacheHost(getEnv(ENV_CACHE_HOST, "localhost"))
                .cachePort(parseInt(System.getenv(ENV_CACHE_PORT), 6379))
                .dbHost(getEnv(ENV_DB_HOST, "localhost"))
                .dbPort(parseInt(System.getenv(ENV_DB_PORT), 5432))
                .maxLocalMessageSize(parseInt(System.getenv(ENV_MAX_LOCAL_MESSAGE_SIZE), DEFAULT_MAX_LOCAL_MESSAGE_SIZE))
                .sessionDataPrefix(getEnv(ENV_SESSION_DATA, DEFAULT_SESSION_DATA_PREFIX))
                .pipelineConfigDir(getEnv(ENV_CONFIG_DIR, DEFAULT_CONFIG_DIR))
                .pipelineConfigVersion(getEnv(ENV_CONFIG_VERSION, DEFAULT_CONFIG_VERSION))
                .pipelineConfigRetryWaitSeconds(parseInt(System.getenv(ENV_CONFIG_RETRY_WAIT_SECONDS), DEFAULT_CONFIG_RETRY_WAIT_SECONDS))
                .pipelineConfigKeyPrefix(getEnv(ENV_CONFIG_KEY_PREFIX, DEFAULT_CONFIG_KEY_PREFIX))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static List<String> parseCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String getEnv(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v.trim() : defaultValue;
    }

    public static final class Builder {
        private List<String> taskQueues = List.of();
        private boolean debugQueueEnabled;
        private String cacheHost = "localhost";
        private int cachePort = 6379;
        private String dbHost = "localhost";
        private int dbPort = 5432;
        private int maxLocalMessageSize = DEFAULT_MAX_LOCAL_MESSAGE_SIZE;
        private String sessionDataPrefix = DEFAULT_SESSION_DATA_PREFIX;
        private String pipelineConfigDir = DEFAULT_CONFIG_DIR;
        private String pipelineConfigVersion = DEFAULT_CONFIG_VERSION;
        private int pipelineConfigRetryWaitSeconds = DEFAULT_CONFIG_RETRY_WAIT_SECONDS;
        private String pipelineConfigKeyPrefix = DEFAULT_CONFIG_KEY_PREFIX;

        public Builder taskQueues(List<String> taskQueues) {
            this.taskQueues = Objects.requireNonNull(taskQueues, "taskQueues");
            return this;
        }

        public Builder debugQueueEnabled(boolean debugQueueEnabled) {
            this.debugQueueEnabled = debugQueueEnabled;
            return this;
        }

        public Builder cacheHost(String cacheHost) {
            this.cacheHost = cacheHost;
            return this;
        }

        public Builder cachePort(int cachePort) {
            this.cachePort = cachePort;
            return this;
        }

        public Builder dbHost(String dbHost) {
            this.dbHost = dbHost;
            return this;
        }

        public Builder dbPort(int dbPort) {
            this.dbPort = dbPort;
            return this;
        }

        public Builder maxLocalMessageSize(int maxLocalMessageSize) {
            this.maxLocalMessageSize = maxLocalMessageSize;
            return this;
        }

        public Builder sessionDataPrefix(String sessionDataPrefix) {
            this.sessionDataPrefix = sessionDataPrefix != null ? sessionDataPrefix : DEFAULT_SESSION_DATA_PREFIX;
            return this;
        }

        public Builder pipelineConfigDir(String pipelineConfigDir) {
            this.pipelineConfigDir = pipelineConfigDir != null ? pipelineConfigDir : DEFAULT_CONFIG_DIR;
            return this;
        }

        public Builder pipelineConfigVersion(String pipelineConfigVersion) {
            this.pipelineConfigVersion = pipelineConfigVersion != null ? pipelineConfigVersion : DEFAULT_CONFIG_VERSION;
            return this;
        }

        public Builder pipelineConfigRetryWaitSeconds(int pipelineConfigRetryWaitSeconds) {
            this.pipelineConfigRetryWaitSeconds = pipelineConfigRetryWaitSeconds;
            return this;
        }

        public Builder pipelineConfigKeyPrefix(String pipelineConfigKeyPrefix) {
            this.pipelineConfigKeyPrefix = pipelineConfigKeyPrefix != null ? pipelineConfigKeyPrefix : DEFAULT_CONFIG_KEY_PREFIX;
            return this;
        }

        public OloConfig build() {
            return new OloConfig(this);
        }
    }
}
