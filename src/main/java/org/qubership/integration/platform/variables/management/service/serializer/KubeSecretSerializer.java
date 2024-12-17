/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.variables.management.service.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesException;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
public class KubeSecretSerializer extends StdSerializer<V1Secret> {

    private final ObjectMapper objectMapper;

    @Autowired
    public KubeSecretSerializer(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        this(null, objectMapper);
    }

    public KubeSecretSerializer(Class<V1Secret> t, @Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        super(t);
        this.objectMapper = objectMapper;
    }

    @Override
    public void serialize(V1Secret secret, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
        jsonGenerator.writeStartObject();

        if (secret.getKind() != null) {
            jsonGenerator.writeStringField(V1Secret.SERIALIZED_NAME_KIND, secret.getKind());
        }
        if (secret.getApiVersion() != null) {
            jsonGenerator.writeStringField(V1Secret.SERIALIZED_NAME_API_VERSION, secret.getApiVersion());
        }
        if (secret.getType() != null) {
            jsonGenerator.writeStringField(V1Secret.SERIALIZED_NAME_TYPE, secret.getType());
        }
        if (secret.getImmutable() != null) {
            jsonGenerator.writeBooleanField(V1Secret.SERIALIZED_NAME_IMMUTABLE, secret.getImmutable());
        }
        if (secret.getMetadata() != null) {
            jsonGenerator.writeFieldName(V1Secret.SERIALIZED_NAME_METADATA);
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField(V1ObjectMeta.SERIALIZED_NAME_NAME, secret.getMetadata().getName());

            if (secret.getMetadata().getLabels() != null) {
                jsonGenerator.writeObjectField(V1ObjectMeta.SERIALIZED_NAME_LABELS, secret.getMetadata().getLabels());
            }
            jsonGenerator.writeEndObject();
        }
        if (secret.getData() != null) {
            writeSecuredVariablesData(jsonGenerator, secret.getData());
        }

        jsonGenerator.writeEndObject();
    }

    private void writeSecuredVariablesData(JsonGenerator jsonGenerator, Map<String, byte[]> data) throws IOException {
        if (MapUtils.isEmpty(data)) {
            return;
        }

        jsonGenerator.writeFieldName(V1Secret.SERIALIZED_NAME_STRING_DATA);
        jsonGenerator.writeStartObject();

        for (Map.Entry<String, byte[]> dataEntry : data.entrySet()) {
            String serializedVariables;
            try {
                Map<String, String> variables = objectMapper.readValue(new String(dataEntry.getValue()), new TypeReference<>() {});
                variables.replaceAll((key, value) -> composeHelmChartExpressionFromKey(key));
                serializedVariables = objectMapper.writeValueAsString(variables);
            } catch (IOException e) {
                log.error("Can't deserialize secured variables for tenant: {}", dataEntry.getKey(), e);
                throw new SecuredVariablesException("Can't deserialize secured variables for tenant: " + dataEntry.getKey(), e);
            }

            jsonGenerator.writeString(serializedVariables);
        }
        jsonGenerator.writeEndObject();
    }

    /**
     * Replacing all:
     * <ul>
     * <li>dots (".") and hyphens ("-") to underscore ("_");</li>
     * <li>lowercase characters to uppercase;</li>
     * <li>camel case to snake case.</li>
     * </ul>
     * Wrapping to Helm Chart expression.
     *
     * @param key incoming string, e.g. "adminTOken-Test.variable"
     * @return Helm Chart expression, e.g. "{{ .Values.&lt;ADMIN_TOKEN_TEST_VARIABLE&gt; }}"
     */
    private String composeHelmChartExpressionFromKey(String key) {
        return "{{ .Values.<" + key.replaceAll("[.\\-]|(?<=[a-z])([A-Z])(?=[a-z]*)", "_$1").toUpperCase() + "> }}";
    }
}