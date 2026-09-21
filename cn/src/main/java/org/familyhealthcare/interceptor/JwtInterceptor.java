package org.familyhealthcare.interceptor;

import org.familyhealthcare.entity.SysRole;
import org.familyhealthcare.entity.SysUser;
import org.familyhealthcare.mapper.SysRoleMapper;
import org.familyhealthcare.mapper.SysUserMapper;
import org.familyhealthcare.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWTinterceptor - verifyToken
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();

        if (path.startsWith("/api/auth/")) {
            return true;
        }

        if (path.equals("/api/health") || path.startsWith("/api/health/")) {
            return true;
        }

        if (path.startsWith("/swagger") || path.startsWith("/v2/api-docs") || path.startsWith("/doc.html")) {
            return true;
        }

        String token = request.getHeader("Authorization");
        if (token == null || token.isEmpty()) {
            writeUnauthorized(response, "You are not signed in. Please sign in first.");
            return false;
        }

        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (!jwtUtil.validateToken(token)) {
            writeUnauthorized(response, "Your session has expired. Please sign in again.");
            return false;
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getDeleted() == null || user.getDeleted() != 0
                || user.getStatus() == null || user.getStatus() != 1) {
            writeUnauthorized(response, "The account does not exist or is disabled.");
            return false;
        }
        request.setAttribute("userId", userId);
        request.setAttribute("username", user.getUsername());

        // queryuserRoleandstorestore
        List<SysRole> roles = sysRoleMapper.selectRolesByUserId(userId);
        List<String> roleCodes = roles.stream().map(SysRole::getRoleCode).collect(Collectors.toList());
        request.setAttribute("roleCodes", roleCodes);

        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
