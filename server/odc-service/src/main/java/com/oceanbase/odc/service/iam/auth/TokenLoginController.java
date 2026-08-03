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
package com.oceanbase.odc.service.iam.auth;

import java.io.IOException;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.auth0.jwt.interfaces.Claim;
import com.oceanbase.odc.common.trace.TraceContextHolder;
import com.oceanbase.odc.core.authority.SecurityManager;
import com.oceanbase.odc.core.authority.exception.AuthenticationException;
import com.oceanbase.odc.core.shared.constant.OrganizationType;
import com.oceanbase.odc.metadb.iam.OrganizationRepository;
import com.oceanbase.odc.service.collaboration.OrganizationResourceMigrator;
import com.oceanbase.odc.service.common.response.Responses;
import com.oceanbase.odc.service.common.response.SuccessResponse;
import com.oceanbase.odc.service.common.util.WebResponseUtils;
import com.oceanbase.odc.service.iam.JwtService;
import com.oceanbase.odc.service.iam.LoginHistoryService;
import com.oceanbase.odc.service.iam.OrganizationMapper;
import com.oceanbase.odc.service.iam.model.JwtConstants;
import com.oceanbase.odc.service.iam.model.LoginHistory;
import com.oceanbase.odc.service.iam.model.Organization;
import com.oceanbase.odc.service.iam.model.User;
import com.oceanbase.odc.service.iam.util.SecurityContextUtils;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 通过 JWT token 自动登录的 REST 接口。
 * 前端从 URL hash 路由中提取 token，调用此接口完成认证。
 */
@Slf4j
@RestController
public class TokenLoginController {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcUserDetailService jdbcUserDetailService;

    @Autowired
    private SecurityManager securityManager;

    @Autowired
    private LoginHistoryService loginHistoryService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    @Qualifier("organizationResourceMigrator")
    private OrganizationResourceMigrator organizationResourceMigrator;

    private final OrganizationMapper organizationMapper = OrganizationMapper.INSTANCE;

    @PostMapping("/api/v2/iam/token-login")
    public void tokenLogin(@RequestBody TokenLoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {
        String token = request.getToken();
        if (StringUtils.isBlank(token)) {
            httpResponse.sendError(HttpStatus.BAD_REQUEST.value(), "Token is required");
            return;
        }

        // 验证 JWT 签名和有效期
        if (!jwtService.verify(token)) {
            log.warn("JWT token verification failed");
            httpResponse.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid or expired token");
            return;
        }

        // 从 JWT claims 中提取用户名
        Map<String, Claim> claims = jwtService.getClaims(token);
        Claim principalClaim = claims.get(JwtConstants.PRINCIPAL);
        if (principalClaim == null || principalClaim.isNull()) {
            log.warn("JWT token does not contain PRINCIPAL claim");
            httpResponse.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid token: missing PRINCIPAL");
            return;
        }

        String username = principalClaim.asString();
        TraceContextHolder.setAccountName(username);

        // 加载用户详情
        UserDetails userDetails = jdbcUserDetailService.loadUserByUsername(username);

        // 创建认证令牌并设置到 SecurityContext
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 记录登录历史
        LoginHistory loginHistory = new LoginHistory();
        loginHistory.setSuccess(true);
        loginHistory.setUserId(TraceContextHolder.getUserId());
        loginHistory.setOrganizationId(TraceContextHolder.getOrganizationId());
        loginHistory.setAccountName(username);
        loginHistory.setLoginTime(OffsetDateTime.now());
        loginHistoryService.record(loginHistory);

        // 切换到用户的 TEAM 组织
        if (userDetails instanceof User) {
            User user = (User) userDetails;
            organizationResourceMigrator.migrate(user);
            List<Organization> belongedOrganizations =
                    organizationRepository.findByUserId(user.getId()).stream()
                            .map(organizationMapper::entityToModel).collect(Collectors.toList());
            Organization team = belongedOrganizations.stream()
                    .filter(organization -> organization.getType() == OrganizationType.TEAM)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                            "User doesn't belong to any TEAM organization, userId=" + user.getId()));
            user.setOrganizationId(team.getId());
            user.setOrganizationType(OrganizationType.TEAM);
            SecurityContextUtils.switchCurrentUserOrganization(user, team, httpRequest, true);
        }

        // Security Framework login
        try {
            securityManager.login(null, null);
        } catch (AuthenticationException e) {
            log.error("Fail to login for security framework", e);
        }

        // 确保 session 被创建（JSession 模式下 SecurityContextPersistenceFilter 会自动持久化）
        httpRequest.getSession(true);

        log.info("JWT token login succeeded for user: {}", username);

        // 返回成功响应
        SuccessResponse<String> successResponse = Responses.success("ok");
        WebResponseUtils.writeJsonObjectWithOkStatus(successResponse, httpRequest, httpResponse);
    }

    @Data
    public static class TokenLoginRequest {
        private String token;
    }
}
