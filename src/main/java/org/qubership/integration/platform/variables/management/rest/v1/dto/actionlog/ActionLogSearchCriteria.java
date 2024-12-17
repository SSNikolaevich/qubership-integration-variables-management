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

package org.qubership.integration.platform.variables.management.rest.v1.dto.actionlog;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Search request for audit log")
public class ActionLogSearchCriteria {

    @Schema(description = "Timestamp of request offset")
    private long offsetTime = 0;

    @Schema(description = "Range in milliseconds")
    private long rangeTime = 0;

    private List<ActionLogFilterRequestDTO> filters = Collections.emptyList();

    public Timestamp getOffsetTime() {
        return new Timestamp(offsetTime);
    }
}