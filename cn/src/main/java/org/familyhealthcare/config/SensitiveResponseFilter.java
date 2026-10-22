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
            "/api/monitoring/snapshot-bounded", "/monitoring/snapshot-bounded",
            "/api/care/backup", "/api/care/backup/preview", "/api/care/backup/restore",
            "/api/care-plans/reports/preview", "/api/care-plans/reports/export"));
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if(path.endsWith("/"))path=path.substring(0,path.length()-1);
        if(path.equals("/api/emergency-assistance")||path.startsWith("/api/emergency-assistance/")||path.equals("/emergency-assistance")||path.startsWith("/emergency-assistance/")||path.equals("/api/care-journey/emergency-capabilities")||path.equals("/care-journey/emergency-capabilities")||path.equals("/api/care-journey/emergency-card")||path.startsWith("/api/care-journey/emergencies")||path.equals("/care-journey/emergency-card")||path.startsWith("/care-journey/emergencies")||path.equals("/api/family-assistance")||path.startsWith("/api/family-assistance/")||path.equals("/family-assistance")||path.startsWith("/family-assistance/")||path.equals("/api/stock-workflows")||path.startsWith("/api/stock-workflows/")||path.equals("/stock-workflows")||path.startsWith("/stock-workflows/")
                ||path.equals("/api/medication-executions")||path.startsWith("/api/medication-executions/")
                ||path.equals("/medication-executions")||path.startsWith("/medication-executions/")||ROUTES.contains(path)||path.equals("/api/alert-workflows")||path.startsWith("/api/alert-workflows/")
                ||path.equals("/alert-workflows")||path.startsWith("/alert-workflows/")
                ||path.equals("/api/care-journey/measurements")||path.startsWith("/api/care-journey/measurements/")
                ||path.equals("/care-journey/measurements")||path.startsWith("/care-journey/measurements/")
                ||path.equals("/api/medical-record/reviews")||path.startsWith("/api/medical-record/reviews/")
                ||path.equals("/medical-record/reviews")||path.startsWith("/medical-record/reviews/")
                ||path.equals("/api/medical-record/corrections")||path.startsWith("/api/medical-record/corrections/")
                ||path.equals("/medical-record/corrections")||path.startsWith("/medical-record/corrections/")
                ||path.equals("/api/common-care-goals")||path.startsWith("/api/common-care-goals/")
                ||path.equals("/common-care-goals")||path.startsWith("/common-care-goals/")
                ||path.startsWith("/api/bp-self-monitor/")||path.startsWith("/bp-self-monitor/")) {
            response.setHeader("Cache-Control","no-store, private");
            response.setHeader("X-Content-Type-Options","nosniff");
        }
        chain.doFilter(request,response);
    }
}
