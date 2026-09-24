package io.mindqa.database.support;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executa um cenário JDBC em uma JVM com ambiente e classpath isolados. */
public final class JdbcScenarioRunner {
    private final Path workingDirectory;

    public JdbcScenarioRunner(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public void run(Map<String, String> environment, String action, String expected,
            String database, String... javaOptions) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = workingDirectory + File.pathSeparator
                + workingDirectory.resolve("fixtures.jar") + File.pathSeparator
                + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path output = Files.createTempFile(workingDirectory, "scenario-", ".log");
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(Arrays.asList(javaOptions));
        boolean explicitFile = Arrays.stream(javaOptions).anyMatch(option -> option.startsWith("-Ddb.config=")
                || option.startsWith("-Ddb.env=")) || environment.containsKey("DB_CONFIG")
                || environment.containsKey("DB_ENV");
        if (!explicitFile && !Files.exists(workingDirectory.resolve("database.properties"))) {
            // O exemplo do projeto está na raiz do classpath; use um arquivo vazio nos
            // cenários só com DB_*.
            Path emptyConfiguration = workingDirectory.resolve("empty-database.properties");
            Files.writeString(emptyConfiguration, "", StandardCharsets.UTF_8);
            command.add("-Ddb.config=" + emptyConfiguration);
        }
        command.addAll(Arrays.asList("-cp", classpath,
                JdbcScenarioProcess.class.getName(), action, expected, database));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().keySet().removeIf(key -> key.startsWith("DB_") || key.startsWith("ORACLE_")
                || key.startsWith("MYSQL_") || key.startsWith("POSTGRESQL_") || key.startsWith("SQLSERVER_"));
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "O cenário excedeu 30 segundos.");
            assertEquals(0, process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }
}
