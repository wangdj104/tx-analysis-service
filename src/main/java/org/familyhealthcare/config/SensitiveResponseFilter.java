package org.familyhealthcare.config;

import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Exact sensitive routes receive private headers even when handler mapping rejects their HTTP method. */
public final class SensitiveResponseFilter extends OncePerRequestFilter implements Ordered {
    private static final Set<String> ROUTES=new HashSet<>(Arrays.asList(
            "/api/health-report/generate", "/api/data-export/csv",
            "/api/care/backup", "/api/care/backup/preview", "/api/care/backup/restore",
            "/api/care-plans/reports/preview", "/api/care-plans/reports/export"));
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if(path.endsWith("/"))path=path.substring(0,path.length()-1);
        if(ROUTES.contains(path)) {
            response.setHeader("Cache-Control","no-store, private");
            response.setHeader("X-Content-Type-Options","nosniff");
        }
        chain.doFilter(request,response);
    }
}
