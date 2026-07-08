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

/**
 * Abstraction over the web-terminal backend. Historically the only implementation spawned a local
 * obclient/psql subprocess ({@link OBClientProxy}). A JDBC-driver-backed implementation
 * ({@code JdbcClientProxy}) executes SQL through {@code ConnectionSession} instead of a subprocess,
 * so the web terminal no longer depends on a locally-installed client binary for that dialect.
 *
 * @author wenniu.ly
 * @date 2020/12/14
 */
public interface ClientProxy {

    void connect(String[] commands);

    default void write(String command) throws Exception {

    }

    default String read() {
        return null;
    }

    void close();

    /**
     * Whether the backend is still alive. For a subprocess backend this is whether the process is
     * running; for a JDBC backend this is whether the session is usable. Used by the scheduled reaper
     * in {@code WebSocketServer} to detect dead terminals.
     *
     * @return {@code true} if the backend can still serve requests
     */
    default boolean isAlive() {
        return true;
    }

    /**
     * Last time the terminal was accessed (a {@code stdin}/{@code ping} message arrived). Used by the
     * scheduled reaper to close idle terminals after the ping timeout.
     *
     * @return epoch millis of last access
     */
    long getLastAccessTime();

    void setLastAccessTime(long accessTime);
}
