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
package com.oceanbase.odc.core.sql.execute.mapper;

import java.io.IOException;
import java.sql.SQLException;

import com.oceanbase.tools.dbbrowser.model.datatype.DataType;

import lombok.NonNull;

/**
 * @author jingtian
 * @date 2024/4/15
 * @since ODC_release_4.3.0
 */
public class OBOracleGeometryMapper implements JdbcColumnMapper {
    @Override
    public Object mapCell(@NonNull CellData data) throws SQLException, IOException {
        return data.getString();
    }

    @Override
    public boolean supports(@NonNull DataType dataType) {
        return "GEOMETRY".equalsIgnoreCase(dataType.getDataTypeName());
    }
}
