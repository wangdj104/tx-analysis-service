package org.familyhealthcare.service;

import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.entity.SysRole;
import org.familyhealthcare.interceptor.PermissionInterceptor;
import org.familyhealthcare.mapper.SysMenuMapper;
import org.familyhealthcare.mapper.SysRoleMapper;
import org.familyhealthcare.mapper.SysRoleMenuMapper;
import org.familyhealthcare.mapper.SysUserRoleMapper;
import org.familyhealthcare.service.impl.SysMenuServiceImpl;
import org.familyhealthcare.service.impl.SysRoleServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformPermissionRegressionTest {
    @Test void patientCannotUsePlatformMenuEndpointEvenWithStaleMenuGrants() throws Exception {
        PermissionInterceptor interceptor = new PermissionInterceptor();
        SysMenuMapper menus = mock(SysMenuMapper.class);
        ReflectionTestUtils.setField(interceptor, "menuMapper", menus);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/menu/list");
        request.setAttribute("userId", 8L);
        request.setAttribute("roleCodes", Collections.singletonList("patient"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        verifyNoInteractions(menus);
    }

    @Test void signedInUserCanChangeOwnPasswordButOnlyAdminCanManagePlatformMenus() throws Exception {
        PermissionInterceptor interceptor = new PermissionInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/changePassword");
        request.setAttribute("userId", 8L);
        request.setAttribute("roleCodes", Collections.singletonList("patient"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        request.setRequestURI("/api/menu/list");
        request.setAttribute("roleCodes", Collections.singletonList("admin"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test void menuTreeDropsPlatformManagementWhilePreservingPatientProfilePage() {
        SysMenuServiceImpl service = new SysMenuServiceImpl();
        SysMenuMapper menus = mock(SysMenuMapper.class);
        SysRoleMapper roles = mock(SysRoleMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", menus);
        ReflectionTestUtils.setField(service, "roleMapper", roles);
        SysRole patient = new SysRole(); patient.setRoleCode("patient");
        SysMenu platform = new SysMenu(); platform.setPermission("menu:manage"); platform.setMenuPath("/system/menu");
        SysMenu profile = new SysMenu(); profile.setPermission("patient:manage"); profile.setMenuPath("/system/patient");
        when(roles.selectRolesByUserId(8L)).thenReturn(Collections.singletonList(patient));
        when(menus.selectMenusByUserId(8L)).thenReturn(Arrays.asList(platform, profile));
        assertEquals(Collections.singletonList(profile), service.getMenusByUserId(8L));
    }

    @Test void deletingRetiredRoleRemovesBothMenuAndUserAssociations() {
        SysRoleServiceImpl service = spy(new SysRoleServiceImpl());
        SysRoleMapper roles = mock(SysRoleMapper.class);
        SysRoleMenuMapper menus = mock(SysRoleMenuMapper.class);
        SysUserRoleMapper users = mock(SysUserRoleMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", roles);
        ReflectionTestUtils.setField(service, "roleMenuMapper", menus);
        ReflectionTestUtils.setField(service, "userRoleMapper", users);
        doReturn(true).when(service).removeById(7L);
        assertTrue(service.deleteRole(7L));
        verify(menus).deleteByRoleId(7L);
        verify(users).deleteByRoleId(7L);
        verify(service).removeById(7L);
    }
}
