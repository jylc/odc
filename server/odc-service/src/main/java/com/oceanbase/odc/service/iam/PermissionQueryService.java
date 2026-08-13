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
package com.oceanbase.odc.service.iam;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.oceanbase.odc.core.authority.util.SkipAuthorize;
import com.oceanbase.odc.core.shared.constant.ResourceType;
import com.oceanbase.odc.metadb.iam.PermissionRepository;
import com.oceanbase.odc.metadb.resourcegroup.ResourceGroupEntity;
import com.oceanbase.odc.metadb.resourcegroup.ResourceGroupRepository;
import com.oceanbase.odc.service.connection.model.ConnectionConfig;
import com.oceanbase.odc.service.iam.auth.AuthenticationFacade;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Lightweight permission queries that bypass the security framework (which loads all user permissions and
 * resource-roles via authorizers) to answer single-resource questions directly against {@code iam_permission}.
 *
 * @author moicena
 */
@Service
@Slf4j
public class PermissionQueryService {

    /**
     * Actions whose mask implies READ (0x3) for an ODC_CONNECTION, see {@code ConnectionPermission.getMaskFromAction}.
     */
    private static final Set<String> CONNECTION_READ_ACTIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("read", "readonlyconnect", "connect", "*")));

    /**
     * Actions whose mask implies READ (0x3) for an ODC_PRIVATE_CONNECTION (plain ResourcePermission semantics).
     */
    private static final Set<String> PRIVATE_CONNECTION_READ_ACTIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("read", "*")));

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private ResourceGroupRepository resourceGroupRepository;

    @Autowired
    private AuthenticationFacade authenticationFacade;

    /**
     * Equivalent to {@code securityManager.isPermitted(ConnectionPermission(connectionId, "read"))} but avoids
     * loading all user permissions/resource-roles via the authorizers, which is expensive when the user belongs
     * to thousands of projects. Only the {@code iam_permission} path matters for an ODC_CONNECTION read check:
     * the resource-role based authorizer produces {@code ResourceRoleBasedPermission}s whose
     * {@code implies(ConnectionPermission)} is always false, so it never contributes to connection read checks.
     */
    @SkipAuthorize("odc internal usage")
    public boolean hasConnectionReadPermission(@NonNull ConnectionConfig dataSource) {
        Long connectionId = dataSource.getId();
        String resourceType = dataSource.resourceType();
        Set<String> identifiers = buildConnectionReadIdentifiers(resourceType, connectionId);
        Set<String> actions = ResourceType.ODC_CONNECTION.name().equals(resourceType)
                ? CONNECTION_READ_ACTIONS
                : PRIVATE_CONNECTION_READ_ACTIONS;
        return permissionRepository.countByUserIdAndOrganizationIdAndResourceIdentifierInAndActionIn(
                authenticationFacade.currentUserId(),
                authenticationFacade.currentOrganizationId(), identifiers, actions) > 0;
    }

    /**
     * Build the set of {@code iam_permission.resource_identifier} values whose expanded SecurityResource
     * {@code implies} the target connection: direct id, type wildcard, global wildcard, and every enabled
     * resource group that contains the connection.
     */
    private Set<String> buildConnectionReadIdentifiers(String resourceType, Long connectionId) {
        Set<String> identifiers = new HashSet<>();
        identifiers.add(resourceType + ":" + connectionId);
        identifiers.add(resourceType + ":*");
        identifiers.add("*");
        // Resource groups only apply to ORGANIZATION-scoped connections; findByConnectionId already restricts
        // visible_scope='ORGANIZATION'. Disabled groups are excluded to match ResourcePermissionExtractor,
        // which does not expand disabled resource groups.
        if (ResourceType.ODC_CONNECTION.name().equals(resourceType)) {
            for (ResourceGroupEntity group : resourceGroupRepository.findByConnectionId(connectionId)) {
                if (group.isEnabled()) {
                    identifiers.add(ResourceType.ODC_RESOURCE_GROUP.name() + ":" + group.getId() + "/"
                            + ResourceType.ODC_CONNECTION.name() + ":*");
                }
            }
        }
        return identifiers;
    }
}
