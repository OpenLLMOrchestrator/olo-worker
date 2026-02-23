package com.olo.executiontree.load;

import com.olo.executiontree.config.PipelineConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationLoaderTest {

    /** ConfigSource that returns empty (simulates no Redis/DB). */
    private static final ConfigSource EMPTY_SOURCE = new ConfigSource() {
        @Override
        public Optional<String> getFromCache(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getFromDb(String queueName, String version) {
            return Optional.empty();
        }
    };

    private static final String MINIMAL_DEFAULT_JSON = """
            {"version":"1.0.0","executionDefaults":{"engine":"TEMPORAL","temporal":{"target":"localhost:7233","namespace":"default","taskQueuePrefix":"olo-"},"activity":{"payload":{"maxAccumulatedOutputKeys":0,"maxResultOutputKeys":0},"defaultTimeouts":{"scheduleToStartSeconds":6000,"startToCloseSeconds":3000,"scheduleToCloseSeconds":30000},"retryPolicy":{"maximumAttempts":3,"initialIntervalSeconds":1,"backoffCoefficient":2,"maximumIntervalSeconds":60,"nonRetryableErrors":[]}}},"pluginRestrictions":[],"featureRestrictions":[],"pipelines":{"default-pipeline":{"name":"default-pipeline","workflowId":"default","inputContract":{"strict":false,"parameters":[]},"variableRegistry":[],"scope":{"plugins":[],"features":[]},"executionTree":{"id":"root","type":"SEQUENCE","children":[]},"outputContract":{"parameters":[]},"resultMapping":[]}}}
            """;

    @TempDir
    Path tempDir;

    private Path configDir;

    @BeforeEach
    void setUp() throws Exception {
        configDir = Files.createDirectories(tempDir.resolve("config"));
    }

    @Test
    void tryLoadOnce_fallsBackToDefaultJsonWhenRedisAndDbEmpty() throws Exception {
        Files.writeString(configDir.resolve("default.json"), MINIMAL_DEFAULT_JSON);
        ConfigurationLoader loader = new ConfigurationLoader(
                EMPTY_SOURCE, configDir, 0, "olo:engine:config");

        Optional<PipelineConfiguration> cfg = loader.tryLoadOnce("default", "any-queue", "1.0");

        assertTrue(cfg.isPresent());
        assertEquals("1.0.0", cfg.get().getVersion());
        assertNotNull(cfg.get().getPipelines());
        assertTrue(cfg.get().getPipelines().containsKey("default-pipeline"));
    }

    @Test
    void loadConfiguration_returnsConfigFromDefaultFileWhenNoRedisOrDb() throws Exception {
        Files.writeString(configDir.resolve("default.json"), MINIMAL_DEFAULT_JSON);
        ConfigurationLoader loader = new ConfigurationLoader(
                EMPTY_SOURCE, configDir, 0, "olo:engine:config");

        PipelineConfiguration config = loader.loadConfiguration("default", "chat-queue-oolama", "1.0");

        assertNotNull(config);
        assertEquals("1.0.0", config.getVersion());
        assertTrue(config.getPipelines().containsKey("default-pipeline"));
    }

    @Test
    void loadConfiguration_prefersQueueFileOverDefault() throws Exception {
        Files.writeString(configDir.resolve("default.json"), MINIMAL_DEFAULT_JSON);
        String queueJson = MINIMAL_DEFAULT_JSON.replace("\"default-pipeline\"", "\"queue-pipeline\"");
        Files.writeString(configDir.resolve("my-queue.json"), queueJson);
        ConfigurationLoader loader = new ConfigurationLoader(
                EMPTY_SOURCE, configDir, 0, "olo:engine:config");

        PipelineConfiguration config = loader.loadConfiguration("default", "my-queue", "1.0");

        assertNotNull(config);
        assertTrue(config.getPipelines().containsKey("queue-pipeline"));
    }

    @Test
    void loadAllQueuesAndPopulateContext_storesReadOnlyCopyInGlobalContext() throws Exception {
        Files.writeString(configDir.resolve("default.json"), MINIMAL_DEFAULT_JSON);
        ConfigSource source = EMPTY_SOURCE;
        List<String> queues = List.of("olo-chat-queue-oolama");
        String version = "1.0";
        int retrySeconds = 0;
        String prefix = "olo:engine:config";

        String tenant = "default";
        GlobalConfigurationContext.loadAllQueuesAndPopulateContext(
                tenant, queues, version, source, configDir, retrySeconds, prefix);

        GlobalContext ctx = GlobalConfigurationContext.get(tenant, "olo-chat-queue-oolama");
        assertNotNull(ctx);
        assertEquals("olo-chat-queue-oolama", ctx.getQueueName());
        PipelineConfiguration cfg = ctx.getConfiguration();
        assertNotNull(cfg);
        assertEquals("1.0.0", cfg.getVersion());
        assertTrue(GlobalConfigurationContext.getContextByTenantAndQueue().containsKey(tenant));
        assertTrue(GlobalConfigurationContext.getContextByTenantAndQueue().get(tenant).containsKey("olo-chat-queue-oolama"));
    }

    @Test
    void loadConfiguration_fromFile_writesBackToConfigSink() throws Exception {
        Files.writeString(configDir.resolve("default.json"), MINIMAL_DEFAULT_JSON);
        AtomicReference<String> cacheKey = new AtomicReference<>();
        AtomicReference<String> cacheJson = new AtomicReference<>();
        AtomicReference<String> dbQueue = new AtomicReference<>();
        AtomicReference<String> dbVersion = new AtomicReference<>();
        AtomicReference<String> dbJson = new AtomicReference<>();
        ConfigSink sink = new ConfigSink() {
            @Override
            public void putInCache(String key, String json) {
                cacheKey.set(key);
                cacheJson.set(json);
            }

            @Override
            public void putInDb(String queueName, String version, String json) {
                dbQueue.set(queueName);
                dbVersion.set(version);
                dbJson.set(json);
            }
        };
        ConfigurationLoader loader = new ConfigurationLoader(
                EMPTY_SOURCE, sink, configDir, 0, "olo:engine:config");

        PipelineConfiguration config = loader.loadConfiguration("default", "some-queue", "1.0");

        assertNotNull(config);
        assertEquals("olo:engine:config:some-queue:1.0", cacheKey.get());
        assertNotNull(cacheJson.get());
        assertTrue(cacheJson.get().contains("1.0.0"), "persisted JSON should contain version");
        assertNotNull(dbJson.get());
        assertEquals("some-queue", dbQueue.get());
        assertEquals("1.0", dbVersion.get());
        assertEquals(cacheJson.get(), dbJson.get(), "same normalized JSON written to cache and DB");
        // Persisted config must have unique node ids (root had "id":"root", so kept; any missing id would be UUID)
        assertTrue(cacheJson.get().contains("\"id\":") || cacheJson.get().contains("\"id\" :"), "persisted JSON should contain node id");
    }

    @Test
    void loadConfiguration_fromFile_persistsConfigWithUniqueNodeIds() throws Exception {
        // Config with a node that has no id - should get a UUID before persist
        String jsonWithoutId = """
            {"version":"1.0","executionDefaults":{"engine":"TEMPORAL","temporal":{"target":"localhost:7233","namespace":"default","taskQueuePrefix":"olo-"},"activity":{"payload":{"maxAccumulatedOutputKeys":0,"maxResultOutputKeys":0},"defaultTimeouts":{"scheduleToStartSeconds":6000,"startToCloseSeconds":3000,"scheduleToCloseSeconds":30000},"retryPolicy":{"maximumAttempts":3,"initialIntervalSeconds":1,"backoffCoefficient":2,"maximumIntervalSeconds":60,"nonRetryableErrors":[]}}},"pluginRestrictions":[],"featureRestrictions":[],"pipelines":{"p":{"name":"p","workflowId":"w","inputContract":{"strict":false,"parameters":[]},"variableRegistry":[],"scope":{"plugins":[],"features":[]},"executionTree":{"type":"SEQUENCE","children":[]},"outputContract":{"parameters":[]},"resultMapping":[]}}}
            """;
        Files.writeString(configDir.resolve("default.json"), jsonWithoutId);
        AtomicReference<String> writtenJson = new AtomicReference<>();
        ConfigSink sink = new ConfigSink() {
            @Override
            public void putInCache(String key, String json) { writtenJson.set(json); }
            @Override
            public void putInDb(String queueName, String version, String json) {}
        };
        ConfigurationLoader loader = new ConfigurationLoader(EMPTY_SOURCE, sink, configDir, 0, "olo:engine:config");
        PipelineConfiguration config = loader.loadConfiguration("default", "q", "1.0");
        assertNotNull(config);
        assertNotNull(writtenJson.get());
        // Root node had no id; after normalize it must have an id (UUID format)
        var root = config.getPipelines().get("p").getExecutionTree();
        assertNotNull(root.getId());
        assertFalse(root.getId().isBlank());
        assertTrue(root.getId().length() >= 32, "node id should be UUID-like");
    }
}
