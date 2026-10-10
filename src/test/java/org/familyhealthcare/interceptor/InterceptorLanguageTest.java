package org.familyhealthcare.interceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;
class InterceptorLanguageTest {
 @AfterEach void resetLocale(){LocaleContextHolder.resetLocaleContext();}
 @Test void unauthorizedAndDeniedJsonUsesChineseForChineseRequests() throws Exception {
  LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
  MockHttpServletResponse unauthorized=new MockHttpServletResponse();
  assertFalse(new JwtInterceptor().preHandle(new MockHttpServletRequest("GET","/api/patient/list"),unauthorized,new Object()));
  assertEquals(401,unauthorized.getStatus()); assertTrue(unauthorized.getContentAsString().contains("您尚未登录，请先登录。"));
  MockHttpServletResponse denied=new MockHttpServletResponse();
  assertFalse(new PermissionInterceptor().preHandle(new MockHttpServletRequest("GET","/api/user/list"),denied,new Object()));
  assertEquals(403,denied.getStatus()); assertTrue(denied.getContentAsString().contains("无权访问此功能"));
 }
}
