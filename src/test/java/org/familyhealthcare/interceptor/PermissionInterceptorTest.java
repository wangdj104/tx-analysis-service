package org.familyhealthcare.interceptor;

import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.mapper.SysMenuMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionInterceptorTest {
    @Test void adminCanAccessProtectedApi() throws Exception {
        PermissionInterceptor interceptor=new PermissionInterceptor();
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/menu/list");
        request.setAttribute("roleCodes",Collections.singletonList("admin"));
        assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
    }
    @Test void userNeedsAssignedPermission() throws Exception {
        PermissionInterceptor interceptor=new PermissionInterceptor();SysMenuMapper mapper=mock(SysMenuMapper.class);ReflectionTestUtils.setField(interceptor,"menuMapper",mapper);
        SysMenu menu=new SysMenu();menu.setPermission("medication:view");when(mapper.selectMenusByUserId(7L)).thenReturn(Collections.singletonList(menu));
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/menu/list");request.setAttribute("userId",7L);request.setAttribute("roleCodes",Collections.singletonList("user"));MockHttpServletResponse response=new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request,response,new Object()));assertEquals(403,response.getStatus());
    }
    @Test void signedInUserCanChangeOwnPasswordWithoutAdminPermission() throws Exception {
        PermissionInterceptor interceptor=new PermissionInterceptor();
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/user/changePassword");
        request.setAttribute("userId",7L);
        request.setAttribute("roleCodes",Collections.singletonList("user"));
        assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
    }
}
