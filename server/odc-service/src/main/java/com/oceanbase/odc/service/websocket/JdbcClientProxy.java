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
package com.oceanbase.odc.service.websocket;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.jdbc.core.StatementCallback;

import com.oceanbase.odc.common.util.StringUtils;
import com.oceanbase.odc.core.session.ConnectionSession;
import com.oceanbase.odc.core.session.ConnectionSessionConstants;
import com.oceanbase.odc.core.session.ConnectionSessionUtil;
import com.oceanbase.odc.service.common.util.SqlUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * A {@link ClientProxy} implementation that bridges the web-terminal WebSocket to server-side JDBC
 * execution, instead of spawning a local {@code psql} subprocess.
 *
 * <p>
 * Because there is no pseudo-terminal (PTY) backing this implementation, it must itself:
 * </p>
 * <ul>
 * <li>echo each keystroke back to the frontend (a PTY does this for free),</li>
 * <li>buffer input until a statement terminator is reached,</li>
 * <li>split multi-statement input via {@link SqlUtils}, execute each statement through the
 * {@link ConnectionSession} console datasource, and</li>
 * <li>render result sets / affected-row counts / errors as text, mimicking {@code psql} output.</li>
 * </ul>
 *
 * <p>
 * Currently scoped to PostgreSQL (the dialect that previously required a local {@code psql} binary).
 * The {@code ConnectionSession} supplied at construction time is owned by
 * {@code ConnectSessionService} and is <b>not</b> closed by this proxy.
 * </p>
 *
 * @author moicena
 */
@Slf4j
public class JdbcClientProxy implements ClientProxy {

    /**
     * Default prompt printed when the terminal is ready for a new statement. {@code psql} uses
     * {@code database=> }; we keep a stable generic prompt that also works before the DB name is
     * resolved.
     */
    private static final String DEFAULT_PROMPT = "postgres=> ";

    /** Carriage-return + line-feed, the EOL sequence expected by xterm. */
    private static final String CRLF = "\r\n";

    /** The DELETE (0x7f) control char sent by xterm for the backspace key. */
    private static final char BACKSPACE = '\u007f';
    private static final char CTRL_C = '\u0003';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char LINE_FEED = '\n';

    private final ConnectionSession connectionSession;
    private final Consumer<String> messageConsumer;
    private final JdbcResultRenderer resultRenderer;

    /** Buffer for the current (possibly multi-line) statement not yet terminated. */
    private final StringBuilder inputBuffer = new StringBuilder();
    /** Last access time, updated on every {@code stdin}/{@code ping}. */
    private volatile long lastAccessTime;
    /** Whether the proxy has been closed. */
    private volatile boolean stopped = false;
    /** The currently executing statement, so {@code Ctrl+C} can cancel it. */
    private volatile Statement currentStatement;

    public JdbcClientProxy(ConnectionSession connectionSession, Consumer<String> messageConsumer) {
        this(connectionSession, messageConsumer, new JdbcResultRenderer());
    }

    public JdbcClientProxy(ConnectionSession connectionSession, Consumer<String> messageConsumer,
            JdbcResultRenderer resultRenderer) {
        this.connectionSession = connectionSession;
        this.messageConsumer = messageConsumer;
        this.resultRenderer = resultRenderer;
        this.lastAccessTime = System.currentTimeMillis();
    }

    @Override
    public void connect(String[] commands) {
        // The argv is irrelevant for the JDBC backend; it exists only for the OBClientProxy contract.
        StringBuilder banner = new StringBuilder();
        banner.append("ODC web terminal (JDBC mode, PostgreSQL)").append(CRLF);
        String schema = safeGetCurrentSchema();
        if (StringUtils.isNotBlank(schema)) {
            banner.append("Current schema: ").append(schema).append(CRLF);
        }
        banner.append("Type SQL statements terminated by ';' and press Enter.").append(CRLF);
        banner.append("psql meta-commands are not supported; use \\q to quit.").append(CRLF);
        banner.append(CRLF).append(DEFAULT_PROMPT);
        emit(banner.toString());
    }

    @Override
    public void write(String data) throws Exception {
        if (stopped || data == null || data.isEmpty()) {
            return;
        }
        lastAccessTime = System.currentTimeMillis();
        // Process input char-by-char so we can echo, handle control keys and detect statement end.
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (ch == CTRL_C) {
                handleCtrlC();
            } else if (ch == BACKSPACE) {
                handleBackspace();
            } else if (ch == CARRIAGE_RETURN || ch == LINE_FEED) {
                // Echo a CRLF for each line break the user typed.
                emit(CRLF);
                inputBuffer.append('\n');
                // A single Enter with no terminator yet -> just continue on the next line (multi-line input).
                if (hasTerminator()) {
                    flushStatements();
                }
            } else {
                // Echo the printable character locally (a PTY would do this).
                emit(String.valueOf(ch));
                inputBuffer.append(ch);
            }
        }
    }

    @Override
    public void close() {
        stopped = true;
        Statement statement = currentStatement;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (Exception e) {
                log.warn("Failed to cancel running statement while closing JDBC terminal proxy", e);
            }
        }
    }

    @Override
    public boolean isAlive() {
        return !stopped && connectionSession != null;
    }

    @Override
    public long getLastAccessTime() {
        return lastAccessTime;
    }

    @Override
    public void setLastAccessTime(long accessTime) {
        this.lastAccessTime = accessTime;
    }

    // ---- input handling -----------------------------------------------------

    private void handleCtrlC() {
        Statement statement = currentStatement;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException e) {
                log.warn("Failed to cancel statement on Ctrl+C", e);
            }
        }
        // Reset the in-progress input buffer.
        inputBuffer.setLength(0);
        emit("^C" + CRLF + DEFAULT_PROMPT);
    }

    private void handleBackspace() {
        int len = inputBuffer.length();
        if (len > 0) {
            inputBuffer.deleteCharAt(len - 1);
            // Move cursor back, overwrite with space, move back again (VT100 backspace).
            emit("\b \b");
        }
    }

    /**
     * Whether the buffered input is ready to be flushed to the executor. The terminal uses two
     * heuristics:
     * <ul>
     * <li>the buffer ends in a {@code ;} (ignoring trailing whitespace) — a normal SQL terminator, or</li>
     * <li>the buffer is a single line whose trimmed content starts with {@code \} — a psql-style
     * meta-command such as {@code \q}, which is itself terminated by Enter rather than {@code ;}.</li>
     * </ul>
     * The authoritative multi-statement split happens later in {@link #executeScript(String)} via
     * {@link SqlUtils}, which is quote/comment aware.
     */
    private boolean hasTerminator() {
        String trimmed = inputBuffer.toString().trim();
        if (trimmed.endsWith(";")) {
            return true;
        }
        // psql meta-commands (e.g. \q, \d) are terminated by Enter, not ';'. They must sit on their own
        // line, so we only treat a single-line backslash input as ready.
        if (trimmed.startsWith("\\") && trimmed.indexOf('\n') < 0) {
            return true;
        }
        return false;
    }

    private void flushStatements() {
        String script = inputBuffer.toString().trim();
        inputBuffer.setLength(0);
        if (script.isEmpty()) {
            emit(DEFAULT_PROMPT);
            return;
        }
        if (isQuitCommand(script)) {
            emit("Connection closed." + CRLF);
            close();
            return;
        }
        try {
            executeScript(script);
        } catch (Exception e) {
            // Unexpected failure outside the per-statement handler; surface it and keep the session alive.
            log.warn("Failed to execute script in JDBC terminal", e);
            emit("ERROR:  " + rootMessage(e) + CRLF + CRLF);
        }
        emit(DEFAULT_PROMPT);
    }

    private boolean isQuitCommand(String script) {
        String trimmed = script.trim();
        return "\\q".equalsIgnoreCase(trimmed) || "\\quit".equalsIgnoreCase(trimmed);
    }

    // ---- execution ----------------------------------------------------------

    /**
     * Split the script into individual statements and execute them sequentially, rendering the output
     * of each. Execution runs on the calling thread (the {@code proxyExecutor} worker in
     * {@code WebSocketServer}).
     */
    private void executeScript(String script) {
        List<String> statements;
        try {
            statements = SqlUtils.split(connectionSession, script, false);
        } catch (Exception e) {
            emit("ERROR:  failed to parse SQL: " + rootMessage(e) + CRLF + CRLF);
            return;
        }
        if (statements.isEmpty()) {
            emit(DEFAULT_PROMPT);
            return;
        }
        for (String sql : statements) {
            if (StringUtils.isBlank(sql)) {
                continue;
            }
            runSingle(sql);
            emit(CRLF);
        }
    }

    private void runSingle(final String sql) {
        try {
            connectionSession.getSyncJdbcExecutor(ConnectionSessionConstants.CONSOLE_DS_KEY)
                    .execute((StatementCallback<String>) statement -> {
                        currentStatement = statement;
                        try {
                            return doExecute(statement, sql);
                        } finally {
                            currentStatement = null;
                        }
                    });
        } catch (Exception e) {
            Throwable cause = rootCause(e);
            String message = rootMessage(cause);
            log.debug("SQL execution error in JDBC terminal: {}", message);
            emit("ERROR:  " + message + CRLF);
        }
    }

    /**
     * Execute one statement and render its results. Mirrors the loop in
     * {@code OdcStatementCallBack#consumeStatement}: iterate {@link Statement#getResultSet()} /
     * {@link Statement#getUpdateCount()} / {@link Statement#getMoreResults()}.
     */
    private String doExecute(Statement statement, String sql) throws SQLException {
        boolean hasResult = statement.execute(sql);
        boolean first = true;
        while (true) {
            if (hasResult) {
                try (ResultSet rs = statement.getResultSet()) {
                    emit(resultRenderer.render(rs));
                }
            } else {
                int updateCount = statement.getUpdateCount();
                if (updateCount == -1 && !first) {
                    // No more results.
                    break;
                }
                if (updateCount >= 0) {
                    emit("UPDATE " + updateCount + CRLF);
                }
            }
            first = false;
            // Advance to the next result. getMoreResults() closing the current ResultSet is expected.
            hasResult = statement.getMoreResults();
            if (!hasResult && statement.getUpdateCount() == -1) {
                break;
            }
        }
        return null;
    }

    // ---- helpers ------------------------------------------------------------

    private void emit(String text) {
        if (messageConsumer != null) {
            messageConsumer.accept(text);
        }
    }

    private String safeGetCurrentSchema() {
        try {
            return ConnectionSessionUtil.getCurrentSchema(connectionSession);
        } catch (Exception e) {
            return null;
        }
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String rootMessage(Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        String message = e.getMessage();
        return StringUtils.isBlank(message) ? e.getClass().getSimpleName() : message;
    }
}
