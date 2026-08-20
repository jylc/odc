/*
 * Copyright (c) 2025 OceanBase.
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

-- Add index on connect_database.project_id to accelerate project-scoped database queries.
-- Without this index, queries like "WHERE project_id IN (...)" perform full table scans,
-- which becomes a severe bottleneck when the number of projects (and the IN list) is large.
-- NOTE: this was originally V_4_3_4_11, which conflicts with the upstream Java migrator
-- V43411RectifyDetectRuleOfTaskTypeMigrate on version 4.3.4.11, so it is renumbered to 4.3.4.13.
CREATE INDEX idx_connect_database_project_id ON connect_database (project_id);
