package org.qubership.integration.platform.variables.management.configuration.datasource;

import org.qubership.cloud.dbaas.client.config.DbaasPostgresDataSourceProperties;
import org.qubership.cloud.dbaas.client.management.PostgresDatasourceCreator;
import org.qubership.cloud.dbaas.client.metrics.DbaaSMetricsRegistrar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({DbaasPostgresDataSourceProperties.class})
@ConditionalOnProperty(value = "qip.datasource.configuration.enabled", havingValue = "true", matchIfMissing = true)
public class PersistenceConfiguration {

    @Bean
    public PostgresDatasourceCreator postgresDatasourceCreator(
        DbaasPostgresDataSourceProperties dbaasDsProperties,
        @Autowired(required = false) DbaaSMetricsRegistrar metricsRegistrar
    ) {
        return new PostgresDatasourceCreator(dbaasDsProperties, metricsRegistrar);
    }
}
