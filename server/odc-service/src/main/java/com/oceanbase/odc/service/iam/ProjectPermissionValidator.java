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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.oceanbase.odc.core.authority.model.DefaultSecurityResource;
import com.oceanbase.odc.core.authority.permission.ComposedPermission;
import com.oceanbase.odc.core.authority.permission.Permission;
import com.oceanbase.odc.core.authority.permission.ProjectPermission;
import com.oceanbase.odc.core.authority.permission.ResourceRoleBasedPermission;
import com.oceanbase.odc.core.authority.util.SkipAuthorize;
import com.oceanbase.odc.core.shared.constant.ResourceRoleName;
import com.oceanbase.odc.core.shared.constant.ResourceType;
import com.oceanbase.odc.core.shared.exception.AccessDeniedException;
import com.oceanbase.odc.service.iam.auth.AuthenticationFacade;
import com.oceanbase.odc.service.iam.auth.AuthorizationFacade;

import lombok.NonNull;

/**
 * @author gaoda.xy
 * @date 2024/1/23 16:38
 */
@Component
public class ProjectPermissionValidator {

    @Autowired
    private AuthenticationFacade authenticationFacade;

    @Autowired
    private AuthorizationFacade authorizationFacade;

    @Autowired
    private ResourceRoleService resourceRoleService;

    @Autowired
    private PermissionQueryService permissionQueryService;

    @SkipAuthorize("internal usage only")
    public void checkProjectRole(@NonNull Long projectId, @NonNull List<ResourceRoleName> roleNames) {
        if (!hasProjectRole(projectId, roleNames)) {
            throw new AccessDeniedException();
        }
    }

    @SkipAuthorize("internal usage only")
    public void checkProjectRole(@NonNull Collection<Long> projectIds, @NonNull List<ResourceRoleName> roleNames) {
        if (!hasProjectRole(projectIds, roleNames)) {
            throw new AccessDeniedException();
        }
    }

    @SkipAuthorize("internal usage only")
    public void checkProjectMember(@NonNull Long projectId) {
        if (!isProjectMember(projectId)) {
            throw new AccessDeniedException();
        }
    }

    /**
     * In-method equivalent of {@code hasProjectRole(projectId, ResourceRoleName.all())}, which goes through
     * securityManager and loads ALL user permissions and resource-roles via the authorizers - extremely
     * expensive when the user belongs to thousands of projects. The role set here is exactly
     * ResourceRoleName.all(), so the resource-role branch equals isProjectMember; the iam_permission branch
     * matches action names literally (ProjectPermission.implies compares literally, so "*" does not count).
     * ComposedPermission.implies is any-match, so OR-ing the two branches is equivalent.
     */
    @SkipAuthorize("internal usage only")
    public boolean isProjectMember(@NonNull Long projectId) {
        return resourceRoleService.isProjectMember(authenticationFacade.currentOrganizationId(),
                authenticationFacade.currentUserId(), projectId)
                || permissionQueryService.hasActionPermission(ResourceType.ODC_PROJECT, projectId,
                        ResourceRoleName.all().stream().map(ResourceRoleName::name).collect(Collectors.toList()));
    }

    @SkipAuthorize("internal usage only")
    public boolean hasProjectRole(@NonNull Long projectId, @NonNull List<ResourceRoleName> roleNames) {
        return hasProjectRole(Collections.singleton(projectId), roleNames);
    }

    @SkipAuthorize("internal usage only")
    public boolean hasProjectRole(@NonNull Collection<Long> projectIds, @NonNull List<ResourceRoleName> roleNames) {
        if (projectIds.isEmpty() || roleNames.isEmpty()) {
            return false;
        }
        List<Permission> permissions = projectIds.stream().filter(Objects::nonNull)
                .map(projectId -> {
                    Permission resourceRolePermission = new ResourceRoleBasedPermission(
                            new DefaultSecurityResource(projectId.toString(), "ODC_PROJECT"), roleNames);
                    Permission projectResourcePermission =
                            new ProjectPermission(new DefaultSecurityResource(projectId.toString(), "ODC_PROJECT"),
                                    roleNames.stream().map(ResourceRoleName::name).collect(
                                            Collectors.toList()));
                    return new ComposedPermission(Arrays.asList(resourceRolePermission, projectResourcePermission));
                }).collect(Collectors.toList());
        return authorizationFacade.isImpliesPermissions(authenticationFacade.currentUser(), permissions);
    }

}
