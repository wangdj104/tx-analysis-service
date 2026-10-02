package org.familyhealthcare.interceptor;

import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.entity.NutritionAssessment;
import org.familyhealthcare.controller.NutritionAssessmentController;
import org.familyhealthcare.service.NutritionAssessmentService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.familyhealthcare.mapper.SysMenuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NutritionPermissionInterceptorTest {
    private PermissionInterceptor interceptor;
    private SysMenuMapper menus;

    @BeforeEach
    void setup() {
        interceptor = new PermissionInterceptor();
        menus = mock(SysMenuMapper.class);
        ReflectionTestUtils.setField(interceptor, "menuMapper", menus);
        when(menus.selectMenusByUserId(7L)).thenReturn(Collections.emptyList());
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/nutrition/list",
            "GET,/api/nutrition/detail/10",
            "POST,/api/nutrition/save",
            "POST,/api/nutrition/calculate",
            "DELETE,/api/nutrition/delete/10"
    })
    void actualAssessmentRoutesRequireTheExistingAssessmentPermission(String method, String path) throws Exception {
        MockHttpServletRequest request = request(method, path, "user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());

        permission("nutrition-assessment:view");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "doctor", "family"})
    void authorizedNutritionRolesKeepAccess(String role) throws Exception {
        permission("nutrition-assessment:view");

        assertTrue(interceptor.preHandle(request("POST", "/api/nutrition/save", role),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void diaryPermissionDoesNotImplyAssessmentPermission() throws Exception {
        permission("nutrition-diary:view");

        assertFalse(interceptor.preHandle(request("GET", "/api/nutrition/list", "user"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("GET", "/api/nutrition-diary/list", "user"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void assessmentPermissionDoesNotBroadenDiaryAccess() throws Exception {
        permission("nutrition-assessment:view");

        assertFalse(interceptor.preHandle(request("GET", "/api/nutrition-diary/list", "user"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("GET", "/api/nutrition-assessment/list", "user"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void anonymousAssessmentAccessIsDeniedAndAdminBehaviorIsPreserved() throws Exception {
        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/api/nutrition/detail/10");
        assertFalse(interceptor.preHandle(anonymous, new MockHttpServletResponse(), new Object()));

        assertTrue(interceptor.preHandle(request("GET", "/api/nutrition/detail/10", "admin"),
                new MockHttpServletResponse(), new Object()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/nutrition/detail/10",
            "/api/nutrition;probe=1/detail/10",
            "/api/nutrition/detail;probe=1/10"
    })
    void mvcMatchedAssessmentRoutesCannotBypassMenuPermissionWithMatrixParameters(String path) throws Exception {
        NutritionAssessmentController controller = new NutritionAssessmentController();
        NutritionAssessmentService service = mock(NutritionAssessmentService.class);
        when(service.getOwnedById(10L)).thenReturn(new NutritionAssessment());
        ReflectionTestUtils.setField(controller, "nutritionAssessmentService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setCustomHandlerMapping(() -> {
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setPathPrefixes(Collections.singletonMap("/api", type -> true));
            return mapping;
        }).addInterceptors(interceptor).build();
        mvc.perform(get(path).requestAttr("userId", 7L)
                .requestAttr("roleCodes", Collections.singletonList("user")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);

        permission("nutrition-assessment:view");
        mvc.perform(get(path).requestAttr("userId", 7L)
                .requestAttr("roleCodes", Collections.singletonList("user")))
                .andExpect(status().isOk());
        verify(service).getOwnedById(10L);
    }

    private void permission(String permission) {
        SysMenu menu = new SysMenu();
        menu.setPermission(permission);
        when(menus.selectMenusByUserId(7L)).thenReturn(Collections.singletonList(menu));
    }

    private MockHttpServletRequest request(String method, String path, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute("userId", 7L);
        request.setAttribute("roleCodes", Collections.singletonList(role));
        return request;
    }
}
