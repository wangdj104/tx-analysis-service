package org.familyhealthcare.service.careplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.careplan.CareExecutionReportContracts.Request;
import org.familyhealthcare.service.careplan.CareExecutionReportRenderer.Export;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A bounded synchronous preparation lane. Never streams or retains a report after delivery. */
@Service
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
public class CareExecutionReportService implements AutoCloseable {
    private final CareExecutionReportAccess access;
    private final CareExecutionReportProjector projector;
    private final CareExecutionReportRenderer renderer;
    private final ObjectMapper json;
    private final Duration timeout;
    private final ThreadPoolExecutor executor;
    @Autowired public CareExecutionReportService(CareExecutionReportAccess access,CareExecutionReportProjector projector,
            CareExecutionReportRenderer renderer,ObjectMapper json) {
        this(access,projector,renderer,json,CareExecutionReportContracts.REQUEST_TIMEOUT);
    }
    CareExecutionReportService(CareExecutionReportAccess access,CareExecutionReportProjector projector,
            CareExecutionReportRenderer renderer,ObjectMapper json,Duration timeout) {
        this.access=Objects.requireNonNull(access);this.projector=Objects.requireNonNull(projector);
        this.renderer=Objects.requireNonNull(renderer);this.json=Objects.requireNonNull(json);this.timeout=Objects.requireNonNull(timeout);
        AtomicInteger threadId=new AtomicInteger();
        executor=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),r->{
            Thread thread=new Thread(r,"care-report-"+threadId.incrementAndGet());thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public PreparedPreview preview(long actorId,Request request) {
        CareExecutionReportBudget budget=CareExecutionReportBudget.start(timeout);
        if(request==null||request.getFormat()!=CareExecutionReportContracts.Format.PREVIEW)throw CareExecutionReportException.invalid();
        return prepare(actorId,request,budget,projection->{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            json.writeValue(budget.output(out),Result.ok(projection.getReport()));
            budget.checkTime();return new PreparedPreview(projection.getReport(),out.toByteArray());
        });
    }
    public Export export(long actorId,Request request) {
        CareExecutionReportBudget budget=CareExecutionReportBudget.start(timeout);
        if(request==null||request.getFormat()==CareExecutionReportContracts.Format.PREVIEW)throw CareExecutionReportException.invalid();
        return prepare(actorId,request,budget,projection->renderer.render(projection.getReport(),request.getFormat(),budget));
    }
    private <T> T prepare(long actorId,Request request,CareExecutionReportBudget budget,Preparation<T> render) {
        Future<T> work;
        try {
            budget.checkTime();
            work=executor.submit(()->{
                budget.checkTime();
                CareExecutionReportAccess.Access initial=access.inspect(actorId,request,budget);
                budget.checkTime();
                CareExecutionReportProjector.Projection projection=projector.project(actorId,request,initial,budget);
                budget.checkTime();access.recheck(actorId,request,projection.getManifest(),budget);
                budget.checkTime();T prepared=render.apply(projection);
                budget.checkTime();access.recheck(actorId,request,projection.getManifest(),budget);
                budget.checkTime();return prepared;
            });
        }catch(RejectedExecutionException full){throw unavailable();}
        try {
            T result=work.get(budget.remainingNanos(),TimeUnit.NANOSECONDS);
            budget.checkTime();return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw CareExecutionReportException.timeout();}
        catch(TimeoutException timeout){throw CareExecutionReportException.timeout();}
        catch(ExecutionException failed){throw safeFailure(failed.getCause());}
        finally {
            // Interrupts are cooperative: an unresponsive library may continue on its occupied worker.
            // The two-worker/two-queue bound still applies; canceled queued requests are removed promptly.
            if(!work.isDone())work.cancel(true);
            executor.remove((Runnable)work);
        }
    }
    private static CareExecutionReportException safeFailure(Throwable failure) {
        // Jackson wraps output-stream budget failures; preserve only our fixed safe exceptions.
        for(int depth=0;failure!=null&&depth<12;depth++,failure=failure.getCause()){
            if(failure instanceof CareExecutionReportException)return(CareExecutionReportException)failure;
            if(failure instanceof org.springframework.dao.QueryTimeoutException||failure instanceof org.springframework.transaction.TransactionTimedOutException)return CareExecutionReportException.timeout();
        }
        return unavailable();
    }
    private static CareExecutionReportException unavailable(){return new CareExecutionReportException(CareExecutionReportException.Code.REPORT_RENDER_UNAVAILABLE);}
    @PreDestroy @Override public void close(){executor.shutdownNow();}
    private interface Preparation<T>{T apply(CareExecutionReportProjector.Projection projection)throws Exception;}
    public static final class PreparedPreview {
        private final CareExecutionReport report;
        private final byte[] json;
        public PreparedPreview(CareExecutionReport report,byte[] json){this.report=Objects.requireNonNull(report);this.json=Objects.requireNonNull(json);}
        public CareExecutionReport getReport(){return report;}
        public byte[] getJson(){return json;}
    }
}
