package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.entity.SysRole;
import org.familyhealthcare.entity.SysUser;
import org.familyhealthcare.service.SysMenuService;
import org.familyhealthcare.service.SysRoleService;
import org.familyhealthcare.service.SysUserService;
import org.familyhealthcare.service.UserLanguagePreferenceService;
import org.familyhealthcare.util.JwtUtil;
import org.familyhealthcare.vo.LoginVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@Api(tags = "confirmcertificatemanagement")
public class AuthController {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private SysRoleService roleService;

    @Autowired
    private SysMenuService menuService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserLanguagePreferenceService languagePreference;

    @PostMapping("/login")
    @ApiOperation("userSign In")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginVO loginVO, HttpServletRequest request) {
        try {
            String token = sysUserService.login(loginVO.getUsername(), loginVO.getPassword());
            SysUser user = sysUserService.getUserByUsername(loginVO.getUsername());
            languagePreference.capture(user.getId(), request.getHeader("Accept-Language"));

            Map<String, Object> result = new HashMap<>();
            result.put("token", token);
            result.put("username", loginVO.getUsername());
            result.put("userId", user.getId());
            result.put("realName", user.getRealName());

            return Result.ok(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/info")
    @ApiOperation("getcurrentSign Inuserinformation")
    public Result<Map<String, Object>> info(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            String token = request.getHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
                if (jwtUtil.validateToken(token)) {
                    userId = jwtUtil.getUserIdFromToken(token);
                }
            }
        }
        if (userId == null) {
            return Result.error("Not signed in");
        }

        SysUser user = sysUserService.getUserById(userId);
        if (user == null) {
            return Result.error("User not found");
        }
        user.setPassword(null);
        languagePreference.capture(userId, request.getHeader("Accept-Language"));

        List<SysRole> roles = roleService.getRolesByUserId(userId);
        List<SysMenu> menus = menuService.getMenusByUserId(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("user", user);
        result.put("roles", roles);
        result.put("menus", menus);
        return Result.ok(result);
    }
}
