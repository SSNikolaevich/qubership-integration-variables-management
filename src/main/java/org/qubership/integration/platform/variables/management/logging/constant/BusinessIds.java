package org.qubership.integration.platform.variables.management.logging.constant;

import java.util.Map;

public final class BusinessIds {
    /**
     * [request_prop_name, log_prop_name]
     * Mappings for businessIdentifiers map building
     */
    public static final Map<String, String> MAPPING = Map.of(
            "commonVariableId", "commonVariableId",
            "securedVariableName", "securedVariableId"
    );

    public static final String BUSINESS_IDS = "businessIdentifiers";

    private BusinessIds() {
    }
}
