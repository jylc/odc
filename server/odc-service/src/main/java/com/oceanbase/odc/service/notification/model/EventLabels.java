/*
 * Copyright (c) 2023 OceanBase.
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
package com.oceanbase.odc.service.notification.model;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * @Author: Lebie
 * @Date: 2023/3/20 15:39
 * @Description: []
 */

public class EventLabels extends TreeMap<String, String> {
    private static final long serialVersionUID = 5693670336877131608L;

    public EventLabels() {
        super(String.CASE_INSENSITIVE_ORDER);
    }

    public EventLabels addLabels(Map<String, String> map) {
        super.putAll(map);
        return this;
    }

    public String putIfNonNull(String key, Object value) {
        if (Objects.nonNull(value)) {
            return put(key, value.toString());
        }
        return null;
    }

    public Long getLongFromString(String key) {
        return Long.parseLong(get(key));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o != null && getClass() == o.getClass();
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode());
    }
}
