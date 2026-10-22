package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionAuthorization;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionRepository;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.integrity;

/** Dormant finite writer storage. Every statement uses the caller's verified connection. */
final class MedicalReviewCommandRepository {
    private static final String B0="SELECT id,patient_id,user_id FROM medical_record FORCE INDEX (PRIMARY) WHERE id=? LIMIT 2";
    private static final String B1="SELECT id,patient_id,user_id,content_version FROM medical_record FORCE INDEX (PRIMARY) WHERE id=? LIMIT 2";
    private static final String S="SELECT record_id,channel,source_generation,patient_id,owner_user_id,content_version,decision_version,cycle,state,last_event_id,current_decision_event_id FROM medical_review_state FORCE INDEX (PRIMARY) WHERE record_id=? ORDER BY channel LIMIT 3";
    private static final String E="SELECT id,record_id,source_generation,channel,operation,before_content_version,content_version,decision_version,cycle,state,actor_id,actor_kind,DATE_FORMAT(occurred_at,'%Y-%m-%dT%H:%i:%s.%f') AS occurred_at_text,occurred_at IS NULL AS occurred_at_null ";
    private static final String L=E+"FROM medical_review_event FORCE INDEX (mrv57_event_version) WHERE record_id=? AND source_generation=? AND channel=? ORDER BY decision_version DESC LIMIT 1";
    private static final String W=E+"FROM medical_review_event FORCE INDEX (mrv57_event_deletion) WHERE record_id=? AND channel='RECORD' AND operation='SOURCE_DELETED' ORDER BY id DESC LIMIT 1";
    private static final String O=E+"FROM medical_review_event FORCE INDEX (mrv57_event_deletion) WHERE record_id=? AND channel=? AND operation=? ORDER BY id DESC LIMIT 1";
    private static final String C=E+"FROM medical_review_event FORCE INDEX (mrv57_event_version) WHERE record_id=? AND source_generation=? AND channel='RECORD' AND decision_version=0 LIMIT 1";
    private static final String G="SELECT id,record_id,source_generation FROM medical_review_event FORCE INDEX (mrv57_event_history) WHERE record_id=? AND source_generation=? ORDER BY channel,id LIMIT 1";
    private static final String INSERT_EVENT="INSERT INTO medical_review_event(record_id,source_generation,channel,operation,before_content_version,content_version,decision_version,cycle,state,actor_id,actor_kind,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String INSERT_STATE="INSERT INTO medical_review_state(record_id,channel,source_generation,patient_id,owner_user_id,content_version,decision_version,cycle,state,last_event_id,current_decision_event_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
    private static final String UPDATE_STATE="UPDATE medical_review_state SET content_version=?,decision_version=?,cycle=?,state=?,last_event_id=?,current_decision_event_id=? WHERE record_id=? AND channel=? AND source_generation=? AND (patient_id=? OR (patient_id IS NULL AND ? IS NULL)) AND (owner_user_id=? OR (owner_user_id IS NULL AND ? IS NULL)) AND content_version=? AND decision_version=? AND cycle=? AND state=? AND last_event_id=? AND (current_decision_event_id=? OR (current_decision_event_id IS NULL AND ? IS NULL))";
    private static final String INSERT_TEXT="INSERT INTO medical_review_text(event_id,record_id,source_generation,text_type,text_value) VALUES(?,?,?,?,?)";
    private static final String INSERT_RECEIPT="INSERT INTO medical_review_command(actor_id,record_id,channel,command_key,source_generation,request_hash,event_id,operation,content_version,decision_version,cycle,state,actor_kind,applied_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String TEXT="SELECT event_id,record_id,source_generation,text_type,OCTET_LENGTH(text_value) AS text_bytes,CHAR_LENGTH(text_value) AS text_points,SHA2(text_value,256) AS text_hash FROM medical_review_text WHERE event_id=? LIMIT 2";

    static final class BindingObservation {
        final int count; final Long id,patient,owner,version;
        BindingObservation(int count,Long id,Long patient,Long owner,Long version) {this.count=count;this.id=id;this.patient=patient;this.owner=owner;this.version=version;}
        void requireIdentity(long record,boolean content) {
            if(count==0)throw MedicalRecordCorrectionAuthorization.denied();
            if(count!=1||positive(id)!=record)throw integrity();
            MedicalReviewContentFingerprint.nullableId(patient);MedicalReviewContentFingerprint.nullableId(owner);
            if(content)positive(version);
        }
        MedicalRecord source(){MedicalRecord s=new MedicalRecord();s.setId(id);s.setPatientId(patient);s.setUserId(owner);s.setContentVersion(version);return s;}
        boolean sameIdentity(BindingObservation b){return Objects.equals(id,b.id)&&Objects.equals(patient,b.patient)&&Objects.equals(owner,b.owner);}
        void requireSame(BindingObservation b){if(!sameIdentity(b))throw MedicalRecordCorrectionAuthorization.denied();if(!Objects.equals(version,b.version))throw integrity();}
    }
    BindingObservation observe(Connection c,long record,boolean content)throws SQLException {
        return query(c,content?B1:B0,r->{
            if(!r.next())return new BindingObservation(0,null,null,null,null);
            Long id=number(r,"id"),patient=number(r,"patient_id"),owner=number(r,"user_id"),version=content?number(r,"content_version"):null;
            return new BindingObservation(r.next()?2:1,id,patient,owner,version);
        },record);
    }
    static final class CurrentState {
        final long record,content,decision,cycle,head;final Channel channel;final String generation;final Long patient,owner,pointer;final State state;
        CurrentState(long record,Channel channel,String generation,Long patient,Long owner,long content,long decision,long cycle,State state,long head,Long pointer){
            this.record=positive(record);this.content=positive(content);this.decision=positive(decision);this.cycle=positive(cycle);this.head=positive(head);
            MedicalReviewContentFingerprint.nullableId(patient);MedicalReviewContentFingerprint.nullableId(owner);MedicalReviewContentFingerprint.nullableId(pointer);
            try{requireStateFacts(generation,channel,state,content,decision,cycle);}catch(WorkflowException failure){throw integrity();}
            if(channel==Channel.DOCTOR&&patient==null)throw integrity();
            if((state==State.APPROVED||state==State.REJECTED)?!Long.valueOf(head).equals(pointer):pointer!=null)throw integrity();
            this.channel=channel;this.generation=generation;this.patient=patient;this.owner=owner;this.pointer=pointer;this.state=state;
        }
        @Override public boolean equals(Object value){if(!(value instanceof CurrentState))return false;CurrentState s=(CurrentState)value;return record==s.record&&content==s.content&&decision==s.decision&&cycle==s.cycle&&head==s.head&&channel==s.channel&&generation.equals(s.generation)&&Objects.equals(patient,s.patient)&&Objects.equals(owner,s.owner)&&Objects.equals(pointer,s.pointer)&&state==s.state;}
        @Override public int hashCode(){return Objects.hash(record,channel,generation,patient,owner,content,decision,cycle,state,head,pointer);}
    }
    static final class CurrentPair {
        final CurrentState doctor,source;
        CurrentPair(CurrentState doctor,CurrentState source){this.doctor=doctor;this.source=source;}
        CurrentState selected(Channel c){return c==Channel.DOCTOR?doctor:source;}
        int size(){return (doctor==null?0:1)+(source==null?0:1);}
        String generation(){return doctor!=null?doctor.generation:source==null?null:source.generation;}
        long maxHead(){return Math.max(doctor==null?0:doctor.head,source==null?0:source.head);}
        CurrentPair replace(CurrentState s){return s.channel==Channel.DOCTOR?new CurrentPair(s,source):new CurrentPair(doctor,s);}
        @Override public boolean equals(Object value){return value instanceof CurrentPair&&Objects.equals(doctor,((CurrentPair)value).doctor)&&Objects.equals(source,((CurrentPair)value).source);}
        @Override public int hashCode(){return Objects.hash(doctor,source);}
    }
    private static final class Event {
        final long id,record,content,decision,cycle,actor;final Long before;final String generation,kind,time;final Channel channel;final Operation operation;final State state;
        Event(ResultSet r)throws SQLException{
            id=positive(number(r,"id"));record=positive(number(r,"record_id"));content=positive(number(r,"content_version"));actor=positive(number(r,"actor_id"));
            Long d=number(r,"decision_version"),y=number(r,"cycle");if(d==null||y==null)throw integrity();decision=d;cycle=y;
            before=number(r,"before_content_version");generation=r.getString("source_generation");kind=r.getString("actor_kind");
            try{channel=Channel.valueOf(r.getString("channel"));operation=Operation.valueOf(r.getString("operation"));state=State.valueOf(r.getString("state"));requireEventFacts(generation,channel,operation,state,before,content,decision,cycle);}
            catch(IllegalArgumentException|NullPointerException|WorkflowException failure){throw integrity();}
            if(!Arrays.asList("ADMIN","ASSIGNED_DOCTOR","OWNER","DELEGATE").contains(kind))throw integrity();
            if(channel==Channel.DOCTOR&&(operation==Operation.APPROVE||operation==Operation.REJECT)&&!"ADMIN".equals(kind)&&!"ASSIGNED_DOCTOR".equals(kind))throw integrity();
            if(!Long.valueOf(0).equals(number(r,"occurred_at_null")))throw integrity();time=wallClock(r.getString("occurred_at_text"));
        }
        void key(long record,String generation,Channel channel,Operation operation){if(this.record!=record||(generation!=null&&!generation.equals(this.generation))||(channel!=null&&channel!=this.channel)||(operation!=null&&operation!=this.operation))throw integrity();}
    }
    CurrentPair currentPair(Connection c,BindingObservation b,Lifecycle lifecycle)throws SQLException{
        CurrentPair pair=query(c,S,r->{
            CurrentState doctor=null,source=null;int n=0;
            while(r.next()){
                if(++n>2)throw integrity();CurrentState s;
                try{s=new CurrentState(positive(number(r,"record_id")),Channel.valueOf(r.getString("channel")),r.getString("source_generation"),number(r,"patient_id"),number(r,"owner_user_id"),positive(number(r,"content_version")),positive(number(r,"decision_version")),positive(number(r,"cycle")),State.valueOf(r.getString("state")),positive(number(r,"last_event_id")),number(r,"current_decision_event_id"));}
                catch(IllegalArgumentException|NullPointerException failure){throw integrity();}
                if(s.record!=b.id||s.content!=b.version||!Objects.equals(s.patient,b.patient)||!Objects.equals(s.owner,b.owner))throw integrity();
                if(s.channel==Channel.DOCTOR){if(doctor!=null||source!=null)throw integrity();doctor=s;}else{if(source!=null)throw integrity();source=s;}
            }
            return new CurrentPair(doctor,source);
        },b.id);
        if(lifecycle.getRecordId()!=b.id||!Objects.equals(pair.generation(),lifecycle.getCurrentGeneration())||(pair.doctor!=null&&pair.source!=null&&!pair.doctor.generation.equals(pair.source.generation)))throw integrity();
        for(Channel channel:new Channel[]{Channel.DOCTOR,Channel.SOURCE}){
            CurrentState state=pair.selected(channel);if(state==null)continue;
            Event event=latest(c,b.id,state.generation,channel);
            if(event==null||event.id!=state.head||event.content!=state.content||event.decision!=state.decision||event.cycle!=state.cycle||event.state!=state.state||event.id<=lifecycle.getLatestDeletionEventId())throw integrity();
        }
        // M is mandatory for EVERY one-state pair, including surviving-target commands.
        if(pair.size()==1&&latest(c,b.id,pair.generation(),pair.doctor==null?Channel.DOCTOR:Channel.SOURCE)!=null)throw integrity();
        return pair;
    }
    private Event latest(Connection c,long record,String generation,Channel channel)throws SQLException{
        Event e=event(c,L,record,generation,channel.name());if(e!=null)e.key(record,generation,channel,null);return e;
    }
    private Event event(Connection c,String sql,Object...args)throws SQLException{
        return query(c,sql,r->{if(!r.next())return null;if(r.getMetaData().getColumnCount()!=14)throw integrity();Event e=new Event(r);if(r.next())throw integrity();return e;},args);
    }
    String generation(Connection c,BindingObservation b,CurrentPair pair,Lifecycle lifecycle)throws SQLException{
        if(pair.size()!=0)return pair.generation();
        Event watermark=event(c,W,b.id);long w=lifecycle.getLatestDeletionEventId();
        if(watermark==null){if(w!=0)throw integrity();}else{watermark.key(b.id,null,Channel.RECORD,Operation.SOURCE_DELETED);if(watermark.id!=w)throw integrity();}
        Map<String,Event> closures=new HashMap<>();
        for(Channel channel:new Channel[]{Channel.DOCTOR,Channel.SOURCE})for(Operation operation:new Operation[]{Operation.REQUEST,Operation.APPROVE,Operation.REJECT,Operation.RESUBMIT,Operation.CONTENT_CHANGED}){
            Event e=event(c,O,b.id,channel.name(),operation.name());if(e==null)continue;e.key(b.id,null,channel,operation);
            if(e.id>w)throw integrity();Event closed=closures.get(e.generation);
            if(closed==null){closed=event(c,C,b.id,e.generation);if(closed==null)throw integrity();closed.key(b.id,e.generation,Channel.RECORD,Operation.SOURCE_DELETED);closures.put(e.generation,closed);}
            if(closed.id<=e.id||closed.id>w)throw integrity();
        }
        String generation=UUID.randomUUID().toString();
        query(c,G,r->{if(r.next()){positive(number(r,"id"));if(positive(number(r,"record_id"))!=b.id||!generation.equals(r.getString("source_generation")))throw integrity();throw integrity();}return null;},b.id,generation);
        return generation;
    }
    static final class Transition {
        final long decision,cycle;final State state;final Long before;
        Transition(long decision,long cycle,State state,Long before){this.decision=decision;this.cycle=cycle;this.state=state;this.before=before;}
    }
    static Transition transition(Channel channel,Operation operation,CurrentState old,long content){
        positive(content);
        boolean initial=old==null;
        boolean legal=initial?(channel==Channel.SOURCE&&(operation==Operation.APPROVE||operation==Operation.REJECT)||channel==Channel.DOCTOR&&operation==Operation.REQUEST)
                :(old.state==State.PENDING&&(operation==Operation.APPROVE||operation==Operation.REJECT)||(old.state==State.APPROVED||old.state==State.REJECTED||old.state==State.SUPERSEDED)&&operation==Operation.RESUBMIT);
        if(!legal)throw conflict("TRANSITION_CONFLICT");
        if(old!=null&&(old.channel!=channel||old.content!=content))throw integrity();
        if(old!=null&&(old.decision==MAX_ID||operation==Operation.RESUBMIT&&old.cycle==MAX_ID))throw conflict("VERSION_EXHAUSTED");
        return new Transition(initial?1:nextVersion(old.decision),initial?1:operation==Operation.RESUBMIT?nextVersion(old.cycle):old.cycle,
                operation==Operation.APPROVE?State.APPROVED:operation==Operation.REJECT?State.REJECTED:State.PENDING,initial?null:content);
    }
    String now(Connection c)throws SQLException{return query(c,"SELECT DATE_FORMAT(CURRENT_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%f') AS wall_clock",r->{if(!r.next())throw integrity();String time=wallClock(r.getString("wall_clock"));if(r.next())throw integrity();return time;});}
    static final class TextDescriptor {
        final String type,hash;final long bytes,points;
        TextDescriptor(Operation operation,String text){type=text==null?null:operation==Operation.APPROVE?"REVIEW_NOTE":operation==Operation.REJECT?"REJECTION_REASON":"RESUBMISSION_REASON";hash=text==null?null:WorkflowData.hash(text);bytes=text==null?0:text.getBytes(StandardCharsets.UTF_8).length;points=text==null?0:text.codePointCount(0,text.length());}
    }
    static final class ExpectedCommit {
        final BindingObservation binding;final CurrentPair pair;final Lifecycle lifecycle;final MedicalReviewReceipt receipt;final TextDescriptor text;
        final String contentFingerprint,legacyFingerprint;final MedicalRecordCorrectionSchema.Status parent9;final MedicalRecordCorrectionRepository.State correction;
        ExpectedCommit(BindingObservation b,CurrentPair p,Lifecycle l,MedicalReviewReceipt r,TextDescriptor t,String f,String legacy,MedicalRecordCorrectionSchema.Status parent9,MedicalRecordCorrectionRepository.State correction){binding=b;pair=p;lifecycle=l;receipt=r;text=t;contentFingerprint=f;legacyFingerprint=legacy;this.parent9=parent9;this.correction=correction;}
    }
    MedicalReviewReceipt persist(Connection c,Command command,BindingObservation b,CurrentPair pair,Transition t,String generation,String kind,String time,long watermark)throws SQLException{
        long event;
        try(PreparedStatement s=c.prepareStatement(INSERT_EVENT,Statement.RETURN_GENERATED_KEYS)){
            s.setQueryTimeout(5);bind(s,b.id,generation,command.getChannel().name(),command.getOperation().name(),t.before,b.version,t.decision,t.cycle,t.state.name(),command.getActorId(),kind,sqlTime(time));one(s.executeUpdate());
            try(ResultSet r=s.getGeneratedKeys()){if(r.getMetaData().getColumnCount()!=1||!r.next())throw integrity();long value=r.getLong(1);if(r.wasNull())throw integrity();event=positive(value);if(r.next())throw integrity();}
        }
        if(event<=watermark||event<=pair.maxHead())throw integrity();
        CurrentState old=pair.selected(command.getChannel());Long pointer=t.state==State.APPROVED||t.state==State.REJECTED?event:null;
        if(old==null)update(c,INSERT_STATE,b.id,command.getChannel().name(),generation,b.patient,b.owner,b.version,t.decision,t.cycle,t.state.name(),event,pointer);
        else update(c,UPDATE_STATE,b.version,t.decision,t.cycle,t.state.name(),event,pointer,old.record,old.channel.name(),old.generation,old.patient,old.patient,old.owner,old.owner,old.content,old.decision,old.cycle,old.state.name(),old.head,old.pointer,old.pointer);
        String text=command.getText();if(text!=null)update(c,INSERT_TEXT,event,b.id,generation,new TextDescriptor(command.getOperation(),text).type,text);
        update(c,INSERT_RECEIPT,command.getActorId(),b.id,command.getChannel().name(),command.getCommandKey(),generation,command.payloadHash(),event,command.getOperation().name(),b.version,t.decision,t.cycle,t.state.name(),kind,sqlTime(time));
        return new MedicalReviewReceipt(command.getActorId(),b.id,command.getChannel().name(),command.getCommandKey(),generation,command.payloadHash(),event,command.getOperation().name(),b.version,t.decision,t.cycle,t.state.name(),kind,time);
    }
    CurrentPair postPair(CurrentPair old,BindingObservation b,MedicalReviewReceipt receipt){return old.replace(new CurrentState(b.id,receipt.channel,receipt.generation,b.patient,b.owner,b.version,receipt.decisionVersion,receipt.cycle,receipt.state,receipt.eventId,receipt.state==State.APPROVED||receipt.state==State.REJECTED?receipt.eventId:null));}
    void requireText(Connection c,MedicalReviewReceipt receipt,TextDescriptor text)throws SQLException{
        query(c,TEXT,r->{
            if(!r.next()){if(text.type!=null)throw integrity();return null;}
            if(text.type==null||positive(number(r,"event_id"))!=receipt.eventId||positive(number(r,"record_id"))!=receipt.recordId||!receipt.generation.equals(r.getString("source_generation"))||!text.type.equals(r.getString("text_type"))||!Long.valueOf(text.bytes).equals(number(r,"text_bytes"))||!Long.valueOf(text.points).equals(number(r,"text_points"))||!text.hash.equals(r.getString("text_hash"))||r.next())throw integrity();return null;
        },receipt.eventId);
    }
    static WorkflowException conflict(String code){
        String message;
        switch(code){case "CONTENT_CONFLICT":message="The medical record content changed. Reload before making a new decision.";break;
            case "LEGACY_CONFLICT":message="The previous source verification changed. Reload before making a new decision.";break;
            case "DECISION_CONFLICT":message="The review decision changed. Reload before making a new decision.";break;
            case "TRANSITION_CONFLICT":message="This review action is not available in the current state.";break;
            case "VERSION_EXHAUSTED":message="This review version cannot be advanced.";break;
            default:throw new IllegalArgumentException("Unknown review conflict.");}
        return new WorkflowException(409,"MEDICAL_REVIEW_"+code,message);
    }
    private static String wallClock(String value){if(value==null)throw integrity();MedicalReviewContentFingerprint.time(value);if(value.compareTo("1000-01-01T00:00:00.000000")<0)throw integrity();return value;}
    private static String sqlTime(String value){return value.replace('T',' ');}
    private static long positive(Long value){return MedicalReviewContentFingerprint.id(value);}
    private static Long number(ResultSet r,String column)throws SQLException{long value=r.getLong(column);return r.wasNull()?null:value;}
    private interface Rows<T>{T read(ResultSet r)throws SQLException;}
    private static <T>T query(Connection c,String sql,Rows<T> rows,Object...args)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){s.setQueryTimeout(5);bind(s,args);try(ResultSet r=s.executeQuery()){return rows.read(r);}}}
    private static void update(Connection c,String sql,Object...args)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){s.setQueryTimeout(5);bind(s,args);one(s.executeUpdate());}}
    private static void bind(PreparedStatement s,Object...args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    private static void one(int count){if(count!=1)throw integrity();}
}
