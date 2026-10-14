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
package com.oceanbase.tools.dbbrowser.stats.postgre;

import java.util.List;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcOperations;

import com.oceanbase.tools.dbbrowser.model.DBSession;
import com.oceanbase.tools.dbbrowser.model.DBTableStats;
import com.oceanbase.tools.dbbrowser.stats.DBStatsAccessor;

import lombok.NonNull;

/**
 * {@link PostgresStatsAccessor}
 *
 * 基于 pg_stat_activity / pg_stat_user_tables 实现会话与表统计， 适用于 PostgreSQL 系协议数据源
 *
 * @author moicena
 * @since 4.3.2
 */
public class PostgresStatsAccessor implements DBStatsAccessor {

    private static final String QUERY_SESSION_FROM_PG_STAT_ACTIVITY =
            "SELECT pid AS id, "
                    + "COALESCE(usename, '') AS username, "
                    + "COALESCE(datname, '') AS database_name, "
                    + "COALESCE(client_addr::text, 'local') AS host, "
                    + "COALESCE(client_addr::text, 'local') AS proxy_host, "
                    + "COALESCE(state, '') AS command, "
                    + "COALESCE(wait_event_type || ':' || wait_event, state, '') AS state, "
                    + "CASE WHEN state = 'active' THEN 'ACTIVE' ELSE 'IDLE' END AS trans_state, "
                    + "NULL AS trans_id, "
                    + "NULL AS sql_id, "
                    + "NULL AS trace_id, "
                    + "EXTRACT(EPOCH FROM (now() - COALESCE(query_start, state_change)))::bigint AS execute_time, "
                    + "CASE WHEN state = 'active' THEN query ELSE NULL END AS active_queries, "
                    + "LEFT(query, 200) AS latest_queries "
                    + "FROM pg_stat_activity "
                    + "WHERE backend_type = 'client backend'";

    private final JdbcOperations jdbcOperations;

    public PostgresStatsAccessor(@NonNull JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public DBTableStats getTableStats(@NonNull String schema, @NonNull String tableName) {
        String sql = "SELECT COALESCE(s.n_live_tup, 0) AS row_count, "
                + "COALESCE(pg_relation_size(c.oid), 0) AS data_size "
                + "FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid "
                + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')";
        DBTableStats stats = new DBTableStats();
        jdbcOperations.query(sql, t -> {
            stats.setRowCount(t.getLong("row_count"));
            stats.setDataSizeInBytes(t.getLong("data_size"));
        }, schema, tableName);
        return stats;
    }

    @Override
    public List<DBSession> listAllSessions() {
        return jdbcOperations.query(QUERY_SESSION_FROM_PG_STAT_ACTIVITY,
                new BeanPropertyRowMapper<>(DBSession.class));
    }

    @Override
    public DBSession currentSession() {
        String sql = QUERY_SESSION_FROM_PG_STAT_ACTIVITY + " AND pid = pg_backend_pid()";
        return jdbcOperations.queryForObject(sql, new BeanPropertyRowMapper<>(DBSession.class));
    }

}
