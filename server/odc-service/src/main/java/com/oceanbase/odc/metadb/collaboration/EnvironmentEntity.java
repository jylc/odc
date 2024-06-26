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
package com.oceanbase.odc.metadb.collaboration;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.GenerationTime;

import com.oceanbase.odc.service.collaboration.environment.model.EnvironmentStyle;

import lombok.Data;

/**
 * @Author: Lebie
 * @Date: 2023/4/19 15:20
 * @Description: []
 */

@Data
@Entity
@Table(name = "collaboration_environment")
public class EnvironmentEntity {
    @Id
    @Column(name = "id", nullable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Generated(GenerationTime.ALWAYS)
    @Column(name = "create_time", insertable = false, updatable = false)
    private Date createTime;

    @Generated(GenerationTime.ALWAYS)
    @Column(name = "update_time", insertable = false, updatable = false)
    private Date updateTime;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "style", nullable = false)
    @Enumerated(value = EnumType.STRING)
    private EnvironmentStyle style;

    /**
     * refer to regulation_ruleset.id
     */
    @Column(name = "ruleset_id", nullable = false)
    private Long rulesetId;

    @Column(name = "is_builtin", nullable = false)
    private Boolean builtIn;

    @Column(name = "is_enabled", updatable = false, nullable = false)
    private Boolean enabled;

    @Column(name = "organization_id", updatable = false, nullable = false)
    private Long organizationId;

    @Column(name = "creator_id", updatable = false, nullable = false)
    private Long creatorId;

    @Column(name = "last_modifier_id", nullable = false)
    private Long lastModifierId;

}
