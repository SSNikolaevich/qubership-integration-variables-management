package org.qubership.integration.platform.variables.management.tenantmanager;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.cloud.framework.contexts.tenant.context.TenantContext;
import org.qubership.cloud.headerstracking.filters.context.RequestIdContext;
import org.qubership.cloud.tenantmanager.client.Tenant;
import org.qubership.cloud.tenantmanager.client.TenantManagerConnector;
import org.qubership.integration.platform.variables.management.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.variables.management.util.DevModeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Component
public class TenantHelperService {
    private final TenantManagerConnector tenantManagerConnector;
    private final TenantConfiguration tenantConfiguration;
    private final DevModeUtil devModeUtil;
    private final TransactionHandler transactionHandler;
    @Getter
    private final String defaultTenant;

    @Autowired
    public TenantHelperService(@Autowired(required = false) TenantManagerConnector tenantManagerConnector,
                               TenantConfiguration tenantConfiguration,
                               DevModeUtil devModeUtil,
                               TransactionHandler transactionHandler) {
        this.tenantManagerConnector = tenantManagerConnector;
        this.tenantConfiguration = tenantConfiguration;
        this.devModeUtil = devModeUtil;
        this.transactionHandler = transactionHandler;
        this.defaultTenant = tenantConfiguration.getDefaultTenant();
    }

    @Transactional(propagation = Propagation.NEVER)
    public void invokeForAllTenants(Consumer<String> callback) {
        invokeForAllTenants(callback, false);
    }

    /**
     *
     * @param runInSeparateThread - set TRUE if this method called in thread when
     *                           hibernate session is already managed by framework (e.g. from REST request)
     */
    @Transactional(propagation = Propagation.NEVER)
    public void invokeForAllTenants(Consumer<String> callback, boolean runInSeparateThread) {
        if (runInSeparateThread) {
            String requestId = RequestIdContext.get();
            Thread thread = new Thread(() -> {
                RequestIdContext.set(requestId);
                invokeForAllTenants(callback, callback);
            });
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            invokeForAllTenants(callback, callback);
        }
    }

    private void invokeForAllTenants(Consumer<String> tenantsCallback, Consumer<String> devmodeCallback) {
        if (!devModeUtil.isDevMode()) {
            if (tenantManagerConnector != null) {
                List<Tenant> tenantList = tenantManagerConnector.getTenantList();
                log.debug("Available tenants: {}", tenantList);
                for (Tenant tenant : tenantList) {
                    TenantContext.set(tenant.getExternalId());
                    transactionHandler.runInNewTransaction(() ->
                            tenantsCallback.accept(TenantContext.get()));
                }
            } else {
                log.error("Failed to invoke callback for all tenants. TenantManagerConnector bean not present");
            }
        } else {
            TenantContext.set(tenantConfiguration.getDefaultTenant());
            transactionHandler.runInNewTransaction(() ->
                    devmodeCallback.accept(TenantContext.get()));
        }
    }

    public Optional<String> getTenantNameById(String tenantId) {
        if (tenantManagerConnector == null) {
            return Optional.empty();
        }

        return tenantManagerConnector.getTenantList().stream()
                .filter(tenant -> StringUtils.equals(tenantId, tenant.getExternalId()))
                .map(Tenant::getName)
                .findFirst();
    }
}
