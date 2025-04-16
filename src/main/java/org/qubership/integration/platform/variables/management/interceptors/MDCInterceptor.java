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

package org.qubership.integration.platform.variables.management.interceptors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.logging.constant.BusinessIds;
import org.qubership.integration.platform.variables.management.logging.constant.ContextHeaders;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class MDCInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        try {
            handleRequestId(request);
            handleBusinessIds(request);
        } catch (Exception e) {
            log.warn("Failed to process logging properties", e);
        }
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView)
            throws Exception {
        MDC.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
    }

    private void handleRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(ContextHeaders.REQUEST_ID_HEADER);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(ContextHeaders.REQUEST_ID, requestId);
    }

    private void handleBusinessIds(HttpServletRequest request) {
        Map<String, Object> idsMap = new HashMap<>();

        Map<String, Object> pathParamsMap = (Map<String, Object>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (pathParamsMap != null) {
            for (Map.Entry<String, Object> pathParamEntry : pathParamsMap.entrySet()) {
                String logProperty = BusinessIds.MAPPING.get(pathParamEntry.getKey());
                if (logProperty != null) {
                    idsMap.put(logProperty, pathParamEntry.getValue());
                }
            }
        }

        Map<String, String[]> queryParamsMap = request.getParameterMap();
        if (queryParamsMap != null) {
            for (Map.Entry<String, String[]> queryParamEntry : queryParamsMap.entrySet()) {
                String logProperty = BusinessIds.MAPPING.get(queryParamEntry.getKey());
                String[] value = queryParamEntry.getValue();
                if (logProperty != null && value.length == 1) {
                    idsMap.put(logProperty, value[0]);
                }
            }
        }

        MDC.put(BusinessIds.BUSINESS_IDS, idsMap.toString());
    }
}
