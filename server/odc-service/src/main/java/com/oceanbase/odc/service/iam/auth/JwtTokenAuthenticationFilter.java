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
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.GenericFilterBean;

import com.auth0.jwt.interfaces.Claim;
import com.oceanbase.odc.common.trace.TraceContextHolder;
import com.oceanbase.odc.service.iam.JwtService;
import com.oceanbase.odc.service.iam.model.JwtConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * 从 URL 参数中提取 JWT token，解析用户名并自动登录的过滤器。
 * 支持通过 {@code ?token=<jwt>} 参数直接访问系统。
 */
@Slf4j
public class JwtTokenAuthenticationFilter extends GenericFilterBean {

    private static final String TOKEN_PARAM = "token";

    private final JwtService jwtService;
    private final JdbcUserDetailService jdbcUserDetailService;

    public JwtTokenAuthenticationFilter(JwtService jwtService, JdbcUserDetailService jdbcUserDetailService) {
        this.jwtService = jwtService;
        this.jdbcUserDetailService = jdbcUserDetailService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 已认证则跳过
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth != null && existingAuth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String token = httpRequest.getParameter(TOKEN_PARAM);
        if (token == null || token.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // 验证 JWT 签名和有效期
            if (!jwtService.verify(token)) {
                log.warn("JWT token verification failed");
                chain.doFilter(request, response);
                return;
            }

            // 从 JWT claims 中提取用户名
            Map<String, Claim> claims = jwtService.getClaims(token);
            Claim principalClaim = claims.get(JwtConstants.PRINCIPAL);
            if (principalClaim == null || principalClaim.isNull()) {
                log.warn("JWT token does not contain PRINCIPAL claim");
                chain.doFilter(request, response);
                return;
            }

            String username = principalClaim.asString();
            TraceContextHolder.setAccountName(username);

            // 加载用户详情并创建认证令牌
            UserDetails userDetails = jdbcUserDetailService.loadUserByUsername(username);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.info("JWT token auto-login succeeded for user: {}", username);
        } catch (Exception e) {
            log.warn("JWT token auto-login failed: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
}
