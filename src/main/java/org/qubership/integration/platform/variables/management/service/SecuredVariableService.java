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

package org.qubership.integration.platform.variables.management.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.persistence.EntityExistsException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.variables.management.kubernetes.SecretUpdateCallback;
import org.qubership.integration.platform.variables.management.model.SecretEntity;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.rest.exception.EmptyVariableFieldException;
import org.qubership.integration.platform.variables.management.rest.exception.SecretNotFoundException;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesException;
import org.qubership.integration.platform.variables.management.rest.exception.SecuredVariablesNotFoundException;
import org.qubership.integration.platform.variables.management.rest.v2.dto.variables.SecretErrorResponse;
import org.qubership.integration.platform.variables.management.service.secrets.SecretService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Slf4j
@Service
public class SecuredVariableService {
    public static final String EMPTY_SECURED_VARIABLE_NAME_ERROR_MESSAGE = "Secured variable's name is empty";

    private final SecretService secretService;
    private final ActionsLogService actionLogger;
    private final YAMLMapper yamlMapper;
    private final CommonVariablesService commonVariablesService;
    private final Lock lock;
    private final ConcurrentMap<String, SecretEntity> securedVariablesSecrets = new ConcurrentHashMap<>();

    @Autowired
    public SecuredVariableService(
            SecretService secretService,
            ActionsLogService actionLogger,
            @Lazy CommonVariablesService commonVariablesService,
            @Qualifier("yamlMapper") YAMLMapper yamlMapper
    ) {
        this.secretService = secretService;
        this.actionLogger = actionLogger;
        this.commonVariablesService = commonVariablesService;
        this.yamlMapper = yamlMapper;
        this.lock = new ReentrantLock(true);
    }

    public Map<String, Set<String>> getAllSecretsVariablesNames() {
        lock.lock();
        try {
            refreshAllVariablesSecrets();
            return getVariablesBySecret().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().keySet()));
        } finally {
            lock.unlock();
        }
    }

    public Set<String> getVariablesForDefaultSecret(boolean failIfSecretNotExist) {
        return getVariablesForSecret(secretService.getDefaultSecretName(), failIfSecretNotExist);
    }

    public Set<String> getVariablesForSecret(String secretName, boolean failIfSecretNotExist) {
        secretName = resolveSecretName(secretName);

        lock.lock();
        try {
            refreshVariablesForSecret(secretName, failIfSecretNotExist);

            SecretEntity secret = securedVariablesSecrets.get(secretName);
            if (secret == null) {
                if (failIfSecretNotExist) {
                    throw SecretNotFoundException.forSecret(secretName);
                } else {
                    return Collections.emptySet();
                }
            }

            return secret.getVariables().keySet();
        } finally {
            lock.unlock();
        }
    }

    public Set<String> addVariablesToDefaultSecret(Map<String, String> newVariables) {
        String secretName = secretService.getDefaultSecretName();
        return addVariables(secretName, newVariables).get(secretName);
    }

    public Map<String, Set<String>> addVariables(String secretName, Map<String, String> newVariables) {
        return addVariables(secretName, newVariables, false);
    }

    public Map<String, Set<String>> addVariables(String secretName, Map<String, String> newVariables, boolean importMode) {
        if (newVariables.isEmpty()) {
            return Collections.singletonMap(secretName, Collections.emptySet());
        }

        Map<String, String> oldVariablesCopy;

        lock.lock();
        try {
            secretName = resolveSecretName(secretName);

            refreshVariablesForSecret(secretName, true);
            SecretEntity secret = securedVariablesSecrets.get(secretName);
            if (secret == null) {
                throw SecretNotFoundException.forSecret(secretName);
            }

            ConcurrentMap<String, String> variables = new ConcurrentHashMap<>(secret.getVariables());
            oldVariablesCopy = new HashMap<>(variables);

            if (secretService.isDefaultSecret(secretName)) {
                validateSecuredVariablesUniqueness(variables, newVariables);
            }

            for (Map.Entry<String, String> securedVariable : newVariables.entrySet()) {
                validateSecuredVariable(securedVariable.getKey(), securedVariable.getValue());
            }

            updateVariablesCache(secretName, secretService.addEntries(secretName, newVariables, variables.isEmpty()));
        } finally {
            lock.unlock();
        }

        for (String name : newVariables.keySet()) {
            logSecuredVariableAction(name, secretName, importMode
                    ? LogOperation.IMPORT
                    : (oldVariablesCopy.containsKey(name) ? LogOperation.UPDATE : LogOperation.CREATE));
        }

        return Collections.singletonMap(secretName, newVariables.keySet());
    }

    public void deleteVariablesFromDefaultSecret(Set<String> variablesNames) {
        deleteVariables(secretService.getDefaultSecretName(), variablesNames);
    }

    public void deleteVariables(String secretName, Set<String> variablesNames) {
        deleteVariables(secretName, variablesNames, true);
    }

    public void deleteVariables(String secretName, Set<String> variablesNames, boolean logOperation) {
        secretName = resolveSecretName(secretName);
        if (CollectionUtils.isEmpty(variablesNames)) {
            return;
        }

        Set<String> existedVariables;

        lock.lock();
        try {
            refreshVariablesForSecret(secretName, true);
            SecretEntity secret = securedVariablesSecrets.get(secretName);
            existedVariables = new HashSet<>(secret.getVariables().keySet());
            if (secret == null) {
                throw SecretNotFoundException.forSecret(secretName);
            }

            updateVariablesCache(secretName, secretService.removeEntries(secretName, variablesNames));
        } finally {
            lock.unlock();
        }

        if (logOperation) {
            final String finalSecretName = secretName;
            variablesNames.stream()
                    .filter(existedVariables::contains)
                    .forEach(name -> logSecuredVariableAction(name, finalSecretName, LogOperation.DELETE));
        }
    }

    public List<SecretErrorResponse> deleteVariablesForMultipleSecrets(Map<String, Set<String>> variablesPerSecret) {
        List<CompletableFuture<Map<String, String>>> secretUpdateFutures = new ArrayList<>();
        Map<String, Throwable> secretUpdateExceptions = new HashMap<>();

        lock.lock();
        ConcurrentMap<String, ConcurrentMap<String, String>> variablesBySecret;
        try {
            refreshAllVariablesSecrets();
            variablesBySecret = getVariablesBySecret();
            for (Map.Entry<String, Set<String>> variablePerSecret : variablesPerSecret.entrySet()) {
                String secretName = resolveSecretName(variablePerSecret.getKey());
                Set<String> variablesToRemove = variablePerSecret.getValue();
                SecretEntity secret = securedVariablesSecrets.get(secretName);
                if (secret == null) {
                    secretUpdateExceptions.put(secretName, SecretNotFoundException.forSecret(secretName));
                    continue;
                }

                try {
                    CompletableFuture<Map<String, String>> future = new CompletableFuture<Map<String, String>>()
                            .whenComplete((secretData, throwable) -> {
                                if (secretData != null) {
                                    updateVariablesCache(secretName, secretData);
                                    return;
                                }
                                if (throwable != null) {
                                    secretUpdateExceptions.put(secretName, throwable);
                                }
                            });
                    secretUpdateFutures.add(future);
                    secretService.removeEntriesAsync(secretName, variablesToRemove, new SecretUpdateCallback(future));
                } catch (Exception e) {
                    secretUpdateExceptions.putIfAbsent(
                            secretName,
                            new SecuredVariablesException("Failed to delete variables from secret: " + secretName, e)
                    );
                }
            }

            CompletableFuture.allOf(secretUpdateFutures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to delete variables", e);
            throw new SecuredVariablesException("Failed to delete variables", e);
        } finally {
            lock.unlock();
        }

        variablesPerSecret.entrySet().stream()
                .filter(entry -> !secretUpdateExceptions.containsKey(entry.getKey()))
                .forEach(entry -> entry.getValue().stream()
                        .filter(variable -> variablesBySecret.get(entry.getKey()).containsKey(variable))
                        .forEach(variable -> logSecuredVariableAction(variable, entry.getKey(), LogOperation.DELETE)));
        if (!secretUpdateExceptions.isEmpty()) {
            List<SecretErrorResponse> errorResponses = new ArrayList<>();
            for (Map.Entry<String, Throwable> entry : secretUpdateExceptions.entrySet()) {
                errorResponses.add(new SecretErrorResponse(entry.getKey(), entry.getValue().getMessage()));
                log.error("Failed to delete variables from secret {}", entry.getKey(), entry.getValue());
            }
            if (secretUpdateExceptions.keySet().containsAll(variablesPerSecret.keySet())) {
                throw new SecuredVariablesException("Failed to delete variables from multiple secrets");
            }
            return errorResponses;
        }

        return Collections.emptyList();
    }

    public String updateVariableInDefaultSecret(String name, String value) {
        updateVariables(secretService.getDefaultSecretName(), Collections.singletonMap(name, value));
        return name;
    }

    public Pair<String, Set<String>> updateVariables(String secretName, Map<String, String> variablesToUpdate) {
        secretName = resolveSecretName(secretName);

        lock.lock();
        try {
            refreshVariablesForSecret(secretName, true);

            ConcurrentMap<String, String> variables = new ConcurrentHashMap<>(
                    securedVariablesSecrets.get(secretName).getVariables()
            );

            for (Map.Entry<String, String> variable : variablesToUpdate.entrySet()) {
                String name = variable.getKey();
                String value = variable.getValue();
                validateSecuredVariable(name, value);

                if (!variables.containsKey(name)) {
                    throw new SecuredVariablesNotFoundException("Cannot find variable " + name);
                }

                variables.put(name, isNull(value) ? "" : value);
            }

            updateVariablesCache(secretName, secretService.updateEntries(secretName, variables));
        } finally {
            lock.unlock();
        }

        final String finalSecretName = secretName;
        variablesToUpdate.keySet().forEach(name -> logSecuredVariableAction(name, finalSecretName, LogOperation.UPDATE));
        return Pair.of(secretName, variablesToUpdate.keySet());
    }

    public Set<String> importVariablesRequest(MultipartFile file) {
        Map<String, String> importedVariables;
        try {
            importedVariables = yamlMapper.readValue(new String(file.getBytes()), new TypeReference<>() {});
        } catch (IOException e) {
            log.error("Unable to convert file to variables {}", e.getMessage());
            throw new RuntimeException("Unable to convert file to variables");
        }
        String secretName = secretService.getDefaultSecretName();
        return addVariables(secretName, importedVariables, true).get(secretName);
    }

    private void validateSecuredVariable(String name, String value) {
        if (StringUtils.isBlank(name)) {
            throw new EmptyVariableFieldException(EMPTY_SECURED_VARIABLE_NAME_ERROR_MESSAGE);
        }
    }

    private void validateSecuredVariablesUniqueness(Map<String, String> currentVariables, Map<String, String> newVariables) {
        Map<String, String> commonVariables = commonVariablesService.getVariables();
        for (Map.Entry<String, String> commonVariable : commonVariables.entrySet()) {
            String name = commonVariable.getKey();
            if (currentVariables.containsKey(name) || newVariables.containsKey(name)) {
                throw new EntityExistsException("Common variable with name " + name + " already exists");
            }
        }
    }

    private ConcurrentMap<String, ConcurrentMap<String, String>> getVariablesBySecret() {
        ConcurrentMap<String, ConcurrentMap<String, String>> variables = new ConcurrentHashMap<>();
        for (Map.Entry<String, SecretEntity> entry : securedVariablesSecrets.entrySet()) {
            variables.put(entry.getKey(), entry.getValue().getVariables());
        }

        return variables;
    }

    private void refreshAllVariablesSecrets() {
        var secrets = secretService.getAllSecretsData();
        securedVariablesSecrets.clear();
        secrets.forEach(this::updateVariablesCache);
    }

    private void refreshVariablesForSecret(String secretName, boolean failIfSecretNotExist) {
        try {
            Map<String, String> secretData = secretService.getSecretData(secretName, failIfSecretNotExist);
            updateVariablesCache(secretName, secretData);
        } catch (SecretNotFoundException e) {
            log.error("Cannot get secured variables from secret", e);
            securedVariablesSecrets.remove(secretName);
            throw e;
        }
    }

    private void updateVariablesCache(String secretName, Map<String, String> variables) {
        securedVariablesSecrets.put(secretName, SecretEntity.builder()
                .secretName(secretName)
                .variables(new ConcurrentHashMap<>(variables))
                .build());
    }

    private String resolveSecretName(@Nullable String secretName) {
        return StringUtils.isBlank(secretName) || "default".equalsIgnoreCase(secretName)
                ? secretService.getDefaultSecretName()
                : secretName;
    }

    private void logSecuredVariableAction(String name, String secretName, LogOperation operation) {
        ActionLog action = ActionLog.builder()
                .entityType(EntityType.SECURED_VARIABLE)
                .entityName(name)
                .parentType(EntityType.SECRET)
                .parentName(secretName)
                .operation(operation)
                .build();
        actionLogger.logAction(action);
    }
}
