package org.qubership.integration.platform.variables.management.tenantmanager;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionHandler {

    @Transactional(propagation = Propagation.REQUIRED)
    public void runInTransaction(Runnable callback) {
        callback.run();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runInNewTransaction(Runnable callback) {
        callback.run();
    }
}
