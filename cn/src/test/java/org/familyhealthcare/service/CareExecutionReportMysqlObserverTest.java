package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.*;
import java.time.Instant;
import java.util.Calendar;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fast native assertion/font-evidence instrumentation tests ONLY; never native MySQL evidence. */
class CareExecutionReportMysqlObserverTest {
    private static final String FIRST="SELECT id,name,UTC_TIMESTAMP(6) AS snapshot_at FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0";

    @Test void checkpointIsAfterDelegateExecutesAndPreservesUtcResult()throws Exception {
        Probe p=new Probe();AtomicBoolean executed=new AtomicBoolean();
        when(p.statement.executeQuery()).thenAnswer(call->{executed.set(true);return p.result;});
        Instant utc=Instant.parse("2026-10-04T21:00:00.123456Z");
        when(p.result.getTimestamp(eq("snapshot_at"),any(Calendar.class))).thenReturn(Timestamp.from(utc));
        p.observer.begin(CareExecutionReportMysqlAssertions.Boundary.SNAPSHOT);
        ExecutorService pool=Executors.newSingleThreadExecutor(r->new Thread(r,"care-report-observer-test"));
        Future<Instant> read=pool.submit(()->{TransactionSynchronizationManager.setActualTransactionActive(true);try{return p.wrapped().executeQuery().getTimestamp("snapshot_at",Calendar.getInstance()).toInstant();}finally{TransactionSynchronizationManager.clear();}});
        try {
            assertTrue(p.observer.reached.await(2,TimeUnit.SECONDS));assertTrue(executed.get(),"A pause before executeQuery returns would not prove an established snapshot");assertFalse(read.isDone());
            p.observer.committed.set(true);p.observer.release.countDown();assertEquals(utc,read.get(2,TimeUnit.SECONDS));assertEquals(utc,p.observer.firstSnapshotTime.get());
            assertEquals(1,p.observer.bodyQueries().size());assertEquals("physical-reader",p.observer.bodyQueries().get(0).id);
            assertTrue(p.observer.bodyQueries().get(0).sql.contains("id=?"));
        }finally{p.observer.end();read.cancel(true);pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void checkpointRejectsActualReadCommitted()throws Exception {Probe p=new Probe();when(p.state.getString(1)).thenReturn("READ-COMMITTED");assertSnapshotRejected(p);}
    @Test void checkpointRejectsActualWritableSession()throws Exception {Probe p=new Probe();when(p.state.getBoolean(2)).thenReturn(false);assertSnapshotRejected(p);}
    @Test void checkpointRejectsJdbcReadOnlyDisagreement()throws Exception {Probe p=new Probe();when(p.connection.isReadOnly()).thenReturn(false);assertSnapshotRejected(p);}
    @Test void checkpointRejectsAutoCommitBody()throws Exception {Probe p=new Probe();when(p.connection.getAutoCommit()).thenReturn(true);assertSnapshotRejected(p);}
    @Test void queryEvidenceRejectsMissingStatementTimeout()throws Exception {
        Probe p=new Probe();p.observer.begin(CareExecutionReportMysqlAssertions.Boundary.NONE);
        p.observer.queries.add(new CareExecutionReportMysqlAssertions.Query(FIRST.toLowerCase(java.util.Locale.ROOT),"reader","REPEATABLE-READ",true,false,0));
        p.observer.queries.add(new CareExecutionReportMysqlAssertions.Query("select count(*) from sys_user","auth","READ-COMMITTED",true,false,30));
        AssertionError error=assertThrows(AssertionError.class,p.observer::assertBodyAndFreshReads);assertTrue(error.getMessage().contains("finite remaining query timeout"));p.observer.end();
    }
    @Test void actualFontBytesMustMatchPreparationDigest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception {
        java.nio.file.Path font=directory.resolve("wqy-microhei.ttf");java.nio.file.Files.write(font,"abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String digest="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        java.nio.file.Files.write(directory.resolve("font-preparation.json"),("{\"faceIndex\":0,\"ttfSha256\":\""+digest+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(digest,CareExecutionReportMysqlAssertions.configuredFontSha256(font));
        java.nio.file.Files.write(font,"abd".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(AssertionError.class,()->CareExecutionReportMysqlAssertions.configuredFontSha256(font));
    }
    @Test void fontEvidenceRejectsSymlink(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception {
        java.nio.file.Path actual=directory.resolve("actual.ttf"),link=directory.resolve("wqy-microhei.ttf");java.nio.file.Files.write(actual,new byte[]{1});java.nio.file.Files.createSymbolicLink(link,actual);
        assertThrows(AssertionError.class,()->CareExecutionReportMysqlAssertions.configuredFontSha256(link));
    }
    private static void assertSnapshotRejected(Probe p)throws Exception {
        p.observer.begin(CareExecutionReportMysqlAssertions.Boundary.NONE);p.observer.directThread=Thread.currentThread();TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(AssertionError.class,()->p.wrapped().executeQuery());verify(p.statement).executeQuery();}finally{p.observer.end();TransactionSynchronizationManager.clear();}
    }
    private static final class Probe {
        final CareExecutionReportMysqlAssertions.Observer observer=new CareExecutionReportMysqlAssertions.Observer();
        final Connection connection=mock(Connection.class);final PreparedStatement statement=mock(PreparedStatement.class);final Statement stateStatement=mock(Statement.class);final ResultSet state=mock(ResultSet.class),result=mock(ResultSet.class);
        Probe()throws Exception {when(connection.createStatement()).thenReturn(stateStatement);when(stateStatement.executeQuery(anyString())).thenReturn(state);when(state.next()).thenReturn(true);when(state.getString(1)).thenReturn("REPEATABLE-READ");when(state.getBoolean(2)).thenReturn(true);when(connection.isReadOnly()).thenReturn(true);when(connection.getAutoCommit()).thenReturn(false);when(statement.getQueryTimeout()).thenReturn(30);when(statement.executeQuery()).thenReturn(result);}
        PreparedStatement wrapped(){return(PreparedStatement)observer.statement(statement,connection,"physical-reader",FIRST);}
    }
}
