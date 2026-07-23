package dev.moonservice.backend.feedback;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FeedbackPersistenceConfiguration.PersistenceProperties.class)
class FeedbackPersistenceConfiguration {
    private static final int DEFAULT_CAPACITY = 2_000;
    private static final int MAXIMUM_CAPACITY = 2_000;
    private static final long MIGRATION_CONNECTION_TIMEOUT_MILLIS = 30_000;
    private static final long RUNTIME_CONNECTION_TIMEOUT_MILLIS = 10_000;
    private static final long CONNECTION_VALIDATION_TIMEOUT_MILLIS = 2_000;
    private static final String DRIVER_CONNECT_TIMEOUT_SECONDS = "5";
    private static final String DRIVER_SOCKET_TIMEOUT_SECONDS = "10";

    @Bean(destroyMethod = "close")
    CalibrationFeedbackRepository calibrationFeedbackRepository(PersistenceProperties properties) {
        if (!properties.isEnabled() || !properties.hasCompleteConnection()) {
            return CalibrationFeedbackRepository.disabled();
        }
        Integer capacity = parseCapacity(properties.getCapacity());
        if (capacity == null || !properties.hasPostgresqlUrl()) {
            return CalibrationFeedbackRepository.unavailable();
        }

        HikariDataSource dataSource = null;
        try {
            migrate(properties);
            dataSource = new HikariDataSource(activeHikariConfig(properties));
            CalibrationFeedbackRepository repository =
                    new CalibrationFeedbackRepository(dataSource, capacity);
            repository.warnIfNearOrFullAtStartup();
            return repository;
        } catch (RuntimeException exception) {
            if (dataSource != null) {
                dataSource.close();
            }
            return CalibrationFeedbackRepository.unavailable();
        }
    }

    private static void migrate(PersistenceProperties properties) {
        try (HikariDataSource dataSource =
                     new HikariDataSource(migrationHikariConfig(properties))) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        }
    }

    private static HikariConfig migrationHikariConfig(PersistenceProperties properties) {
        return hikariConfig(
                properties, "moon-feedback-migration", MIGRATION_CONNECTION_TIMEOUT_MILLIS);
    }

    private static HikariConfig activeHikariConfig(PersistenceProperties properties) {
        return hikariConfig(properties, "moon-feedback", RUNTIME_CONNECTION_TIMEOUT_MILLIS);
    }

    private static HikariConfig hikariConfig(
            PersistenceProperties properties,
            String poolName,
            long connectionTimeoutMillis
    ) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(properties.normalizedJdbcUrl());
        config.setUsername(properties.normalizedUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(connectionTimeoutMillis);
        config.setValidationTimeout(CONNECTION_VALIDATION_TIMEOUT_MILLIS);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", DRIVER_CONNECT_TIMEOUT_SECONDS);
        config.addDataSourceProperty("socketTimeout", DRIVER_SOCKET_TIMEOUT_SECONDS);
        return config;
    }

    private static Integer parseCapacity(String rawCapacity) {
        if (rawCapacity == null) {
            return DEFAULT_CAPACITY;
        }
        try {
            int capacity = Integer.parseInt(rawCapacity.strip());
            return capacity > 0 && capacity <= MAXIMUM_CAPACITY ? capacity : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @ConfigurationProperties(prefix = "moon.feedback.persistence")
    public static final class PersistenceProperties {
        private boolean enabled;
        private String jdbcUrl;
        private String username;
        private String password;
        private String capacity;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getCapacity() {
            return capacity;
        }

        public void setCapacity(String capacity) {
            this.capacity = capacity;
        }

        boolean hasCompleteConnection() {
            return !normalizedJdbcUrl().isEmpty()
                    && !normalizedUsername().isEmpty()
                    && password != null
                    && !password.isEmpty();
        }

        boolean hasPostgresqlUrl() {
            return normalizedJdbcUrl().startsWith("jdbc:postgresql:");
        }

        String normalizedJdbcUrl() {
            return jdbcUrl == null ? "" : jdbcUrl.strip();
        }

        String normalizedUsername() {
            return username == null ? "" : username.strip();
        }
    }
}
