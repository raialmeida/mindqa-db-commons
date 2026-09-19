package br.com.mindqa.database;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Cache limitado de snapshots; não mantém arquivos abertos nem executa I/O sob lock. */
final class DatabaseConfigurationCache {
    private static final int MAX_ENTRIES = 32;
    private final Map<Key, DatabaseConfiguration> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long generation;

    synchronized long generation() {
        return generation;
    }

    synchronized DatabaseConfiguration get(Key key) {
        return entries.get(key);
    }

    synchronized DatabaseConfiguration putIfAbsent(Key key, DatabaseConfiguration configuration, long expectedGeneration) {
        // Uma leitura iniciada antes da limpeza não pode repopular o cache depois dela.
        if (generation != expectedGeneration) {
            return configuration;
        }
        DatabaseConfiguration cached = entries.get(key);
        if (cached != null) {
            return cached;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
        entries.put(key, configuration);
        return configuration;
    }

    synchronized void clear() {
        entries.clear();
        generation++;
    }

    static final class Key {
        private final String location;
        private final boolean explicit;
        private final ClassLoader loader;
        private final Map<String, String> environment;

        Key(String location, boolean explicit, ClassLoader loader, Map<String, String> environment) {
            this.location = location;
            this.explicit = explicit;
            this.loader = loader;
            this.environment = environment;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return explicit == key.explicit && loader == key.loader
                    && location.equals(key.location) && environment.equals(key.environment);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location, explicit, System.identityHashCode(loader), environment);
        }
    }
}
