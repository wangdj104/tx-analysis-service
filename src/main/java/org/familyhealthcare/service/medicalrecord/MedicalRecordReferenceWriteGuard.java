package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.service.alertworkflow.AlertWorkflowException;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.service.medicalreview.MedicalReviewSchema;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** One closed reference invocation, never a transaction owner or a public write capability. */
final class MedicalRecordReferenceWriteGuard {
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager transactions;
    private final DataSource dataSource;
    private final Thread thread=Thread.currentThread();
    private final ConnectionHolder holder;
    private final SimpleConnectionHandle handle;
    private final Connection connection;
    private boolean armed,completed,commitProved;

    MedicalRecordReferenceWriteGuard(JdbcTemplate jdbc,DataSourceTransactionManager transactions)throws SQLException {
        this.jdbc=jdbc;this.transactions=transactions;dataSource=jdbc.getDataSource();
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        if(resource==null||resource.getClass()!=ConnectionHolder.class)throw MedicalReviewSchema.notReady();
        holder=(ConnectionHolder)resource;
        if(holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw MedicalReviewSchema.notReady();
        handle=(SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();verify();
    }
    void verify()throws SQLException {
        if(Thread.currentThread()!=thread||jdbc.getDataSource()!=dataSource||transactions.getDataSource()!=dataSource
                ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||TransactionSynchronizationManager.getResource(dataSource)!=holder
                ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)
            throw MedicalReviewSchema.notReady();
        try{if(holder.getConnection()!=connection)throw MedicalReviewSchema.notReady();}catch(IllegalStateException invalid){throw MedicalReviewSchema.notReady();}
        if(connection==null||connection.isClosed()||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw MedicalReviewSchema.notReady();
    }
    Connection connection()throws SQLException {verify();return connection;}
    int timeout(){return holder.hasTimeout()?Math.min(5,holder.getTimeToLiveInSeconds()):5;}
    void arm()throws SQLException {
        verify();if(armed)return;armed=true;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCompletion(){if(!commitProved)holder.setRollbackOnly();}
        });
    }
    void complete(MedicalRecordReferenceVersions.WriteProof proof)throws SQLException {
        verify();if(!armed||completed)throw MedicalRecordReferenceVersions.changed();
        proof.verify();completed=true;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){
                try{verify();proof.verify();proof.verifyGraph();commitProved=true;}
                catch(Throwable failure){throw failure(failure);}
            }
        });
        proof.verifyGraph();
    }
    void close(){if(armed&&!completed)holder.setRollbackOnly();}
    RuntimeException failure(Throwable failure) {
        if(armed)holder.setRollbackOnly();
        if(failure instanceof Error)throw(Error)failure;
        if(failure instanceof WorkflowException||failure instanceof CarePlanException||failure instanceof AlertWorkflowException)return(RuntimeException)failure;
        return MedicalReviewSchema.notReady();
    }
}
