package org.familyhealthcare.service.medicalrecord;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.familyhealthcare.entity.AlertRecord;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.Anchor;
import org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.Kind;
import org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.ReferenceFrame;
import org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.ReferenceSnapshot;
import org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Current scalar decoration of an already-authorized, fixed historical alert selection. */
@Component
public class MedicalRecordAlertProjection {
    private final JdbcTemplate jdbc;
    private final SqlSessionFactory sessions;
    private final Configuration configuration;
    private final Environment environment;
    private final DataSource dataSource;
    private final MedicalRecordValidityProjection validity;
    private final MedicalRecordReferenceVersions references;
    private final MedicalRecordReviewAccess access;

    public MedicalRecordAlertProjection(JdbcTemplate jdbc, SqlSessionFactory sessions,
            MedicalRecordValidityProjection validity, MedicalRecordReferenceVersions references,
            MedicalRecordReviewAccess access) {
        this.jdbc=jdbc; this.sessions=sessions; this.validity=validity;
        this.references=references; this.access=access;
        dataSource=jdbc==null?null:jdbc.getDataSource();
        configuration=sessions==null?null:sessions.getConfiguration();
        environment=configuration==null?null:configuration.getEnvironment();
    }

    public void project(List<AlertRecord> authorizedRows) {
        if(authorizedRows==null||authorizedRows.isEmpty())return;
        List<Occurrence> occurrences=new ArrayList<>();
        SortedMap<Long,Expected> expected=new TreeMap<>(); Set<Long> conflicting=new HashSet<>();
        for(AlertRecord row:authorizedRows) {
            if(row==null||!medical(row))continue;
            neutral(row);
            if(!id(row.getId())||!id(row.getPatientId())||!id(row.getSourceId()))continue;
            Expected item=new Expected(row.getId(),row.getPatientId(),row.getSourceId());
            occurrences.add(new Occurrence(row,item));
            Expected prior=expected.putIfAbsent(item.id,item);
            if(prior!=null&&!prior.same(item))conflicting.add(item.id);
        }
        for(Long conflict:conflicting)expected.remove(conflict);
        if(expected.isEmpty())return;
        Long actor=CurrentUserUtil.getCurrentUserId();
        if(!id(actor))throw sessionChanged();
        Thread thread=Thread.currentThread();
        try {
            context(actor,thread);
            access.requireReadAdmission(Collections.emptyList(),actor);
            context(actor,thread);
            Map<Long,Header> discovered=discover(expected,actor,thread);
            SortedMap<Long,Group> groups=new TreeMap<>();
            Map<Long,Header> sourceHeaders=new HashMap<>(); Set<Long> invalidPatients=new HashSet<>();
            for(Header header:discovered.values()) {
                Group group=groups.computeIfAbsent(header.patient,Group::new);
                group.anchors.put(header.id,header);
                Header prior=sourceHeaders.putIfAbsent(header.record,header);
                if(prior!=null&&!prior.sameSource(header)) {
                    invalidPatients.add(prior.patient);invalidPatients.add(header.patient);
                }
                group.headers.putIfAbsent(header.record,header.nativeHeader());
            }
            List<Anchor> anchors=new ArrayList<>(); Map<Long,Group> admitted=new LinkedHashMap<>();
            for(Group group:groups.values()) {
                if(invalidPatients.contains(group.patient)||!admit(group,actor,thread))continue;
                admitted.put(group.patient,group);
                for(Header header:group.anchors.values())anchors.add(header.anchor());
            }
            if(anchors.isEmpty())return;
            context(actor,thread);
            ReferenceFrame frame=references.readLegacyAlertFrame(anchors,validity.open());
            context(actor,thread);
            Map<Long,ReferenceSnapshot> staged=staged(frame,anchors);
            Set<Long> released=new HashSet<>(); SortedMap<Long,Expected> releaseExpected=new TreeMap<>();
            for(Group group:admitted.values()) {
                if(admit(group,actor,thread))released.add(group.patient);
                for(Header header:group.anchors.values())releaseExpected.put(header.id,expected.get(header.id));
            }
            Map<Long,Header> fresh=discover(releaseExpected,actor,thread);
            for(Group group:admitted.values())for(Header header:group.anchors.values())
                if(!header.same(fresh.get(header.id)))released.remove(group.patient);
            context(actor,thread);
            access.requireReadAdmission(Collections.emptyList(),actor);
            context(actor,thread);
            for(Occurrence occurrence:occurrences) {
                Expected item=expected.get(occurrence.expected.id);
                if(item==null||!released.contains(item.patient)||!item.matches(occurrence.row))continue;
                ReferenceSnapshot snapshot=staged.get(item.id);if(snapshot==null)continue;
                occurrence.row.setSourceStatus(snapshot.status);
                occurrence.row.setSourceBindingStatus(snapshot.bindingStatus);
                occurrence.row.setCurrentSourceStatus(snapshot.currentSourceStatus);
                occurrence.row.setSourceQualificationSemantics(snapshot.qualificationSemantics.name());
                occurrence.row.setSourceQualificationAt(snapshot.checkedAt.toString());
            }
        } catch(SessionChanged failure) {
            // A prior actor's selected rows must not be released under a new session identity.
            throw failure;
        } catch(TransactionException failure) {
            transactionFailure(failure,actor,thread);
        } catch(WorkflowException|DataAccessException|IllegalStateException failure) {
            identity(actor,thread);
            // Expected unavailable observations have one neutral public shape, with no reason or proof detail.
        }
    }

    private boolean admit(Group group,long actor,Thread thread) {
        context(actor,thread);
        try {access.requireReadAdmission(group.headers.values(),actor);context(actor,thread);return true;}
        catch(SessionChanged failure){throw failure;}
        catch(TransactionException failure){transactionFailure(failure,actor,thread);return false;}
        catch(WorkflowException|DataAccessException|IllegalStateException failure){context(actor,thread);return false;}
    }

    private Map<Long,Header> discover(SortedMap<Long,Expected> expected,long actor,Thread thread) {
        Map<Long,Header> result=new LinkedHashMap<>(); List<Expected> all=new ArrayList<>(expected.values());
        for(int offset=0;offset<all.size();offset+=100) {
            List<Expected> chunk=all.subList(offset,Math.min(offset+100,all.size()));
            Set<Long> requested=new LinkedHashSet<>();List<Object> parameters=new ArrayList<>();
            for(Expected item:chunk){requested.add(item.id);parameters.add(item.id);parameters.add(item.patient);parameters.add(item.item);}
            String sql="SELECT v.id,v.patient_id,i.id AS item_id,i.record_id,r.user_id,r.content_version,r.verification_status"
                    +" FROM alert_record v FORCE INDEX (PRIMARY) JOIN medical_record_item i ON i.id=v.source_id"
                    +" AND v.source_type='MEDICAL_RECORD_ITEM' AND (v.alert_type IS NULL OR v.alert_type<>'MEASUREMENT_FOLLOW_UP')"
                    +" JOIN medical_record r ON r.id=i.record_id AND r.patient_id=v.patient_id WHERE "
                    +String.join(" OR ",Collections.nCopies(chunk.size(),"(v.id=? AND v.patient_id=? AND i.id=?)"))
                    +" ORDER BY v.id LIMIT "+(chunk.size()+1);
            context(actor,thread);
            Map<Long,Header> rows=jdbc.query(connection->{
                context(actor,thread);
                if(connection.isClosed()||!connection.getAutoCommit())throw unavailable();
                PreparedStatement statement=connection.prepareStatement(sql);statement.setQueryTimeout(5);
                for(int i=0;i<parameters.size();i++)statement.setObject(i+1,parameters.get(i));
                return statement;
            },rs->{
                if(rs.getMetaData().getColumnCount()!=7)throw unavailable();
                Map<Long,Header> found=new LinkedHashMap<>();long previous=0;
                while(rs.next()) {
                    Header header=new Header(number(rs,"id"),number(rs,"patient_id"),number(rs,"item_id"),
                            number(rs,"record_id"),nullable(rs,"user_id"),number(rs,"content_version"),rs.getString("verification_status"));
                    if(found.size()>=chunk.size()||!requested.contains(header.id)||header.id<=previous
                            ||!expected.get(header.id).same(header)||found.put(header.id,header)!=null)throw unavailable();
                    previous=header.id;
                }
                return found;
            });
            context(actor,thread);result.putAll(rows);
        }
        return result;
    }

    private Map<Long,ReferenceSnapshot> staged(ReferenceFrame frame,List<Anchor> anchors) {
        Set<Long> requested=new LinkedHashSet<>();for(Anchor anchor:anchors)requested.add(anchor.id);
        if(frame==null||frame.checkedAt==null||frame.qualificationSemantics!=MedicalReviewQualificationReader.Semantics.CURRENT_CHECK
                ||!frame.snapshots.keySet().equals(Collections.singleton(Kind.LEGACY_ALERT)))throw unavailable();
        Map<Long,ReferenceSnapshot> values=frame.snapshots.get(Kind.LEGACY_ALERT);
        if(values==null||!values.keySet().equals(requested))throw unavailable();
        for(ReferenceSnapshot value:values.values())if(value==null||value.status==null||value.bindingStatus==null
                ||value.currentSourceStatus==null||value.qualificationSemantics!=frame.qualificationSemantics
                ||!frame.checkedAt.equals(value.checkedAt))throw unavailable();
        return values;
    }

    private static void identity(long actor,Thread thread) {
        if(Thread.currentThread()!=thread||!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId()))throw sessionChanged();
    }
    private static void transactionFailure(TransactionException failure,long actor,Thread thread) {
        Throwable application=failure instanceof TransactionSystemException
                ?((TransactionSystemException)failure).getApplicationException():null;
        if(application instanceof Error)throw (Error)application;
        if(failure.getCause() instanceof Error)throw (Error)failure.getCause();
        if(application instanceof SessionChanged)throw (SessionChanged)application;
        if(application!=null&&!(application instanceof WorkflowException)
                &&!(application instanceof DataAccessException)&&!(application instanceof IllegalStateException)) {
            if(application instanceof RuntimeException)throw (RuntimeException)application;
            throw failure;
        }
        // Infrastructure failures and the existing expected callback families cannot release a prior actor's rows.
        identity(actor,thread);
    }
    private void context(long actor,Thread thread) {
        identity(actor,thread);
        if(dataSource==null||jdbc.getDataSource()!=dataSource||sessions==null||sessions.getConfiguration()!=configuration
                ||environment==null||configuration.getEnvironment()!=environment||environment.getDataSource()!=dataSource
                ||!(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)
                ||validity==null||references==null||access==null
                ||TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw unavailable();
        validity.requireDataSource(dataSource);access.requireDataSource(dataSource);
    }
    private static boolean medical(AlertRecord row){return "MEDICAL_RECORD_ITEM".equals(row.getSourceType())&&!"MEASUREMENT_FOLLOW_UP".equals(row.getAlertType());}
    private static void neutral(AlertRecord row){row.setSourceStatus("SOURCE_UNAVAILABLE");row.setSourceBindingStatus(null);row.setCurrentSourceStatus("SOURCE_UNAVAILABLE");row.setSourceQualificationSemantics(null);row.setSourceQualificationAt(null);}
    private static boolean id(Long value){return value!=null&&value>0&&value<=9007199254740991L;}
    private static long number(ResultSet rs,String column)throws SQLException {Long value=nullable(rs,column);if(value==null)throw unavailable();return value;}
    private static Long nullable(ResultSet rs,String column)throws SQLException {long value=rs.getLong(column);if(rs.wasNull())return null;if(value<=0||value>9007199254740991L)throw unavailable();return value;}
    private static IllegalStateException unavailable(){return new IllegalStateException("The current alert source could not be verified.");}
    private static SessionChanged sessionChanged(){return new SessionChanged();}
    private static final class SessionChanged extends IllegalStateException {SessionChanged(){super("The alert result could not be verified for the current session.");}}
    private static class Expected {
        final long id,patient,item;
        Expected(long id,long patient,long item){this.id=id;this.patient=patient;this.item=item;}
        boolean same(Expected other){return other!=null&&id==other.id&&patient==other.patient&&item==other.item;}
        boolean matches(AlertRecord row){return medical(row)&&Long.valueOf(id).equals(row.getId())&&Long.valueOf(patient).equals(row.getPatientId())&&Long.valueOf(item).equals(row.getSourceId());}
    }
    private static final class Header extends Expected {
        final long record,version;final Long owner;final String review;
        Header(long id,long patient,long item,long record,Long owner,long version,String review){super(id,patient,item);this.record=record;this.owner=owner;this.version=version;this.review=review;}
        boolean sameSource(Header other){return other!=null&&record==other.record&&patient==other.patient&&version==other.version&&Objects.equals(owner,other.owner)&&Objects.equals(review,other.review);}
        boolean same(Header other){return super.same(other)&&sameSource(other);}
        Anchor anchor(){return new Anchor(id,patient,record,item);}
        MedicalRecord nativeHeader(){MedicalRecord value=new MedicalRecord();value.setId(record);value.setPatientId(patient);value.setUserId(owner);value.setContentVersion(version);value.setVerificationStatus(review);return value;}
    }
    private static final class Group {
        final long patient;final SortedMap<Long,Header> anchors=new TreeMap<>();final SortedMap<Long,MedicalRecord> headers=new TreeMap<>();
        Group(long patient){this.patient=patient;}
    }
    private static final class Occurrence {
        final AlertRecord row;final Expected expected;
        Occurrence(AlertRecord row,Expected expected){this.row=row;this.expected=expected;}
    }
}
