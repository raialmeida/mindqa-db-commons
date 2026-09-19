package br.com.mindqa.database;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigurationCacheTest {
    @AfterEach
    void clearCache() {
        DatabaseService.clearConfigurationCache();
    }

    @Test
    void keepsReloadingByDefault() {
        ResourceLoader loader = new ResourceLoader("DB_NAME=antes");
        assertEquals("antes", load(loader, Map.of()).get("DB_NAME"));
        loader.content = "DB_NAME=depois";
        assertEquals("depois", load(loader, Map.of()).get("DB_NAME"));
        assertEquals(2, loader.reads.get());
    }

    @Test
    void reusesFileEnabledSnapshotUntilExplicitInvalidation() {
        ResourceLoader loader = new ResourceLoader("DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=antes");
        DatabaseConfiguration first = load(loader, Map.of());
        loader.content = "DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=depois";
        assertSame(first, load(loader, Map.of()));
        assertEquals(1, loader.reads.get());
        DatabaseService.clearConfigurationCache();
        assertEquals("depois", load(loader, Map.of()).get("DB_NAME"));
        assertEquals(2, loader.reads.get());
    }

    @Test
    void environmentOverridesFileIncludingDisablingCache() {
        ResourceLoader loader = new ResourceLoader("db.config.cache.enabled=true\nDB_NAME=arquivo");
        Map<String, String> environment = Map.of("DB_CONFIG_CACHE_ENABLED", "false", "DB_NAME", "ambiente");
        assertEquals("ambiente", load(loader, environment).get("DB_NAME"));
        load(loader, environment);
        assertEquals(2, loader.reads.get());
        Map<String, String> enabled = Map.of("DB_CONFIG_CACHE_ENABLED", "true", "DB_NAME", "outro");
        assertEquals("outro", load(loader, enabled).get("DB_NAME"));
        load(loader, enabled);
        assertEquals(3, loader.reads.get());
    }

    @Test
    void isolatesSourcesProfilesClassLoadersAndEnvironment() {
        ResourceLoader loader = new ResourceLoader("DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=primeiro");
        DatabaseConfiguration first = load(loader, Map.of());
        Properties selectors = new Properties();
        selectors.setProperty("db.env", "qa");
        loader.content = "DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=perfil";
        assertEquals("perfil", DatabaseConfigurationLoader.load(Map.of(), selectors, loader).get("DB_NAME"));
        selectors.setProperty("db.config", "classpath:outro.properties");
        loader.content = "DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=explicito";
        assertEquals("explicito", DatabaseConfigurationLoader.load(Map.of(), selectors, loader).get("DB_NAME"));
        assertSame(first, load(loader, Map.of()));
        assertEquals("override", load(loader, Map.of("DB_NAME", "override")).get("DB_NAME"));
        ResourceLoader other = new ResourceLoader("DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=outro-loader");
        assertEquals("outro-loader", load(other, Map.of()).get("DB_NAME"));
    }

    @Test
    void sharesWarmSnapshotAcrossThreadsWithoutReopeningFile() throws Exception {
        ResourceLoader loader = new ResourceLoader("DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=qa");
        DatabaseConfiguration expected = load(loader, Map.of());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<DatabaseConfiguration>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(executor.submit(() -> load(loader, Map.of())));
            }
            for (Future<DatabaseConfiguration> call : calls) {
                assertSame(expected, call.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loader.reads.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedReadIsNotCached() {
        ResourceLoader loader = new ResourceLoader("invalid=\\uXXXX");
        Map<String, String> environment = Map.of("DB_CONFIG_CACHE_ENABLED", "true");
        assertThrows(IllegalStateException.class, () -> load(loader, environment));
        loader.content = "DB_NAME=corrigido";
        assertEquals("corrigido", load(loader, environment).get("DB_NAME"));
        assertEquals(2, loader.reads.get());
    }

    @Test
    void invalidationDoesNotAllowInFlightReadToRepopulateOldSnapshot() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ResourceLoader loader = new ResourceLoader("DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=antes") {
            @Override
            public InputStream getResourceAsStream(String name) {
                InputStream input = super.getResourceAsStream(name);
                if (reads.get() == 1) {
                    reading.countDown();
                    try {
                        assertTrue(resume.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
                return input;
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DatabaseConfiguration> inFlight = executor.submit(() -> load(loader, Map.of()));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            DatabaseService.clearConfigurationCache();
            loader.content = "DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=depois";
            resume.countDown();
            assertEquals("antes", inFlight.get(5, TimeUnit.SECONDS).get("DB_NAME"));
            assertEquals("depois", load(loader, Map.of()).get("DB_NAME"));
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void boundsSnapshotsAndReloadsEvictedSources() {
        ResourceLoader loader = new ResourceLoader("DB_CONFIG_CACHE_ENABLED=true\nDB_NAME=qa");
        DatabaseConfiguration first = load(loader, Map.of());
        for (int i = 0; i < 32; i++) {
            load(loader, Map.of("DB_ENV", "ambiente" + i));
        }
        assertNotSame(first, load(loader, Map.of()));
        assertEquals(34, loader.reads.get());
    }

    private static DatabaseConfiguration load(ClassLoader loader, Map<String, String> environment) {
        return DatabaseConfigurationLoader.load(environment, new Properties(), loader);
    }

    private static class ResourceLoader extends ClassLoader {
        volatile String content;
        final AtomicInteger reads = new AtomicInteger();

        ResourceLoader(String content) {
            super(null);
            this.content = content;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            reads.incrementAndGet();
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
