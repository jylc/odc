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
package com.oceanbase.odc.plugin.connect.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

import org.pf4j.Extension;

import com.oceanbase.odc.common.util.JdbcOperationsUtil;
import com.oceanbase.odc.plugin.connect.obmysql.OBMySQLSessionExtension;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link PostgresSessionExtension}
 *
 * @author moicena
 * @since 4.3.4
 */
@Extension
@Slf4j
public class PostgresSessionExtension extends OBMySQLSessionExtension {

    /**
     * PostgreSQL 的连接标识为后端进程 PID，父子类里 select connection_id()/ OceanBaseConnection 强转均不适用于 PgConnection
     */
    @Override
    public String getConnectionId(Connection connection) {
        return JdbcOperationsUtil.getJdbcOperations(connection)
                .queryForObject("select pg_backend_pid()", String.class);
    }

    @Override
    public String getCurrentSchema(Connection connection) {
        return JdbcOperationsUtil.getJdbcOperations(connection)
                .queryForObject("select current_schema()", String.class);
    }

    @Override
    public void switchSchema(Connection connection, String schemaName) throws SQLException {
        String currentSchema = getCurrentSchema(connection);
        if (Objects.equals(currentSchema, schemaName)) {
            return;
        }
        JdbcOperationsUtil.getJdbcOperations(connection)
                .execute("set search_path to \"" + schemaName.replace("\"", "\"\"") + "\"");
    }

    @Override
    public String getVariable(Connection connection, String variableName) {
        try {
            return JdbcOperationsUtil.getJdbcOperations(connection).queryForObject(
                    "select current_setting('" + variableName.replace("'", "''") + "')", String.class);
        } catch (Exception e) {
            log.warn("Failed to get variable {}, message={}", variableName, e.getMessage());
        }
        return null;
    }

}
