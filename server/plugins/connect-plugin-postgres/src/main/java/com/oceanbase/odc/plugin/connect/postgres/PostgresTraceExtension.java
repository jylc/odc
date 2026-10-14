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

import java.sql.SQLException;
import java.sql.Statement;

import org.pf4j.Extension;

import com.oceanbase.odc.core.sql.execute.model.SqlExecTime;
import com.oceanbase.odc.plugin.connect.api.TraceExtensionPoint;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link PostgresTraceExtension}
 *
 * PostgreSQL 没有类似 OB full-link trace（last_trace_id）或 MySQL show profile 的 服务端执行画像；EXPLAIN (ANALYZE)
 * 会二次执行语句，不能用于获取真实耗时。 返回空的 {@link SqlExecTime}，客户端耗时由调用方的 StopWatch 统计， traceEmptyReason 由调用方
 * OdcStatementCallBack 设置。
 *
 * @author moicena
 * @since 4.3.2
 */
@Slf4j
@Extension
public class PostgresTraceExtension implements TraceExtensionPoint {

    @Override
    public SqlExecTime getExecuteDetail(Statement statement, String version) throws SQLException {
        return new SqlExecTime();
    }

}
