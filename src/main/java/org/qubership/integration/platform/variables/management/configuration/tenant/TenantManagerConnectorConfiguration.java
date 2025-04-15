package org.qubership.integration.platform.variables.management.configuration.tenant;

import org.qubership.cloud.maas.client.impl.http.HttpClient;
import org.qubership.cloud.security.core.auth.M2MManager;
import org.qubership.cloud.tenantmanager.client.TenantManagerConnector;
import org.qubership.cloud.tenantmanager.client.impl.TenantManagerConnectorImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("default")
public class TenantManagerConnectorConfiguration {
    @Bean
    HttpClient maasHttpClient(@Autowired M2MManager m2MManager) {
        return new HttpClient(() -> m2MManager.getToken().getTokenValue());
    }

    @Bean
    TenantManagerConnector tenantManagerConnector(HttpClient httpClient) {
        return new TenantManagerConnectorImpl(httpClient);
    }
}
