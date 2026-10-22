package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionAuthorization;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;

/** Fixed lifecycle queries. No enrollment, review payload writer, or transaction ownership. */
final class MedicalReviewNativeRepository {
    private static final String B0="SELECT id,patient_id,user_id FROM medical_record FORCE INDEX (PRIMARY) WHERE id=? LIMIT 2";
    private static final String B1="SELECT id,patient_id,user_id,content_version FROM medical_record FORCE INDEX (PRIMARY) WHERE id=? LIMIT 2";
    private static final String S="SELECT record_id,channel,source_generation,patient_id,owner_user_id,content_version,decision_version,cycle,state,last_event_id,current_decision_event_id FROM medical_review_state FORCE INDEX (PRIMARY) WHERE record_id=? ORDER BY channel LIMIT 3";
    private static final String E="SELECT id,record_id,source_generation,channel,operation,before_content_version,content_version,decision_version,cycle,state,actor_id,actor_kind,DATE_FORMAT(occurred_at,'%Y-%m-%dT%H:%i:%s.%f') AS occurred_at_text,occurred_at IS NULL AS occurred_at_null ";
    private static final String L=E+"FROM medical_review_event FORCE INDEX (mrv57_event_version) WHERE record_id=? AND source_generation=? AND channel=? ORDER BY decision_version DESC LIMIT 1";
    private static final String W=E+"FROM medical_review_event FORCE INDEX (mrv57_event_deletion) WHERE record_id=? AND channel='RECORD' AND operation='SOURCE_DELETED' ORDER BY id DESC LIMIT 1";
    private static final String O=E+"FROM medical_review_event FORCE INDEX (mrv57_event_deletion) WHERE record_id=? AND channel=? AND operation=? ORDER BY id DESC LIMIT 1";
    private static final String C=E+"FROM medical_review_event FORCE INDEX (mrv57_event_version) WHERE record_id=? AND source_generation=? AND channel='RECORD' AND decision_version=0 LIMIT 1";
    private static final String INSERT_EVENT="INSERT INTO medical_review_event(record_id,source_generation,channel,operation,before_content_version,content_version,decision_version,cycle,state,actor_id,actor_kind,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String UPDATE_STATE="UPDATE medical_review_state SET content_version=?,decision_version=?,cycle=?,state=?,last_event_id=?,current_decision_event_id=? WHERE record_id=? AND channel=? AND source_generation=? AND patient_id <=> ? AND owner_user_id <=> ? AND content_version=? AND decision_version=? AND cycle=? AND state=? AND last_event_id=? AND current_decision_event_id <=> ?";

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
    static final class Event {
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

    static final class Proof {
        final CurrentPair pair; final Event watermark;
        Proof(CurrentPair pair, Event watermark) { this.pair=pair; this.watermark=watermark; }
        long watermarkId() { return watermark==null?0:watermark.id; }
    }
    Proof proof(Connection c, BindingObservation b, Lifecycle lifecycle) throws SQLException {
        CurrentPair pair=query(c,S,r->{
            CurrentState doctor=null,source=null;int n=0;
            while(r.next()) {
                if(++n>2)throw integrity();
                CurrentState state;
                try { state=new CurrentState(positive(number(r,"record_id")),Channel.valueOf(r.getString("channel")),r.getString("source_generation"),number(r,"patient_id"),number(r,"owner_user_id"),positive(number(r,"content_version")),positive(number(r,"decision_version")),positive(number(r,"cycle")),State.valueOf(r.getString("state")),positive(number(r,"last_event_id")),number(r,"current_decision_event_id")); }
                catch(IllegalArgumentException|NullPointerException failure){throw integrity();}
                if(state.record!=b.id||state.content!=b.version||!Objects.equals(state.patient,b.patient)||!Objects.equals(state.owner,b.owner))throw integrity();
                if(state.channel==Channel.DOCTOR){if(doctor!=null||source!=null)throw integrity();doctor=state;}
                else {if(source!=null)throw integrity();source=state;}
            }
            return new CurrentPair(doctor,source);
        },b.id);
        if(pair.size()==2&&!pair.doctor.generation.equals(pair.source.generation))throw integrity();
        Event watermark=event(c,W,b.id);
        if(watermark!=null)watermark.key(b.id,null,Channel.RECORD,Operation.SOURCE_DELETED);
        long w=watermark==null?0:watermark.id;
        if(lifecycle!=null&&(lifecycle.getRecordId()!=b.id||!Objects.equals(lifecycle.getCurrentGeneration(),pair.generation())||lifecycle.getLatestDeletionEventId()!=w))throw integrity();
        for(Channel channel:new Channel[]{Channel.DOCTOR,Channel.SOURCE}) {
            CurrentState state=pair.selected(channel); if(state==null)continue;
            Event head=latest(c,b.id,state.generation,channel);
            if(head==null||head.id!=state.head||head.content!=state.content||head.decision!=state.decision||head.cycle!=state.cycle||head.state!=state.state||head.id<=w)throw integrity();
        }
        if(pair.size()==1&&latest(c,b.id,pair.generation(),pair.doctor==null?Channel.DOCTOR:Channel.SOURCE)!=null)throw integrity();
        if(pair.size()==0) {
            Map<String,Event> closures=new HashMap<>();
            for(Channel channel:new Channel[]{Channel.DOCTOR,Channel.SOURCE})
                for(Operation operation:new Operation[]{Operation.REQUEST,Operation.APPROVE,Operation.REJECT,Operation.RESUBMIT,Operation.CONTENT_CHANGED}) {
                    Event old=event(c,O,b.id,channel.name(),operation.name());if(old==null)continue;
                    old.key(b.id,null,channel,operation);if(old.id>w)throw integrity();
                    Event closed=closures.get(old.generation);
                    if(closed==null){closed=closure(c,b.id,old.generation);if(closed==null)throw integrity();closures.put(old.generation,closed);}
                    if(closed.id<=old.id||closed.id>w)throw integrity();
                }
            requirePayloadAbsent(c,b.id);
        }
        return new Proof(pair,watermark);
    }
    private Event latest(Connection c,long record,String generation,Channel channel)throws SQLException {
        Event e=event(c,L,record,generation,channel.name());if(e!=null)e.key(record,generation,channel,null);return e;
    }
    Event closure(Connection c,long record,String generation)throws SQLException {
        Event e=event(c,C,record,generation);if(e!=null)e.key(record,generation,Channel.RECORD,Operation.SOURCE_DELETED);return e;
    }
    private Event event(Connection c,String sql,Object...args)throws SQLException {
        return query(c,sql,r->{if(!r.next())return null;if(r.getMetaData().getColumnCount()!=14)throw integrity();Event e=new Event(r);if(r.next())throw integrity();return e;},args);
    }
    static void sameEvent(Event expected,Event actual) {
        if(expected==null||actual==null){if(expected!=actual)throw integrity();return;}
        if(expected.id!=actual.id||expected.record!=actual.record||expected.content!=actual.content||expected.decision!=actual.decision||expected.cycle!=actual.cycle||expected.actor!=actual.actor||!Objects.equals(expected.before,actual.before)||!expected.generation.equals(actual.generation)||!expected.kind.equals(actual.kind)||!expected.time.equals(actual.time)||expected.channel!=actual.channel||expected.operation!=actual.operation||expected.state!=actual.state)throw integrity();
    }
    String now(Connection c)throws SQLException {
        return query(c,"SELECT DATE_FORMAT(CURRENT_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%f') AS wall_clock",r->{if(!r.next())throw integrity();String time=wallClock(r.getString("wall_clock"));if(r.next())throw integrity();return time;});
    }
    private Event append(Connection c,BindingObservation b,String generation,Channel channel,Operation operation,long content,long decision,long cycle,State state,long actor,String kind,String time,long after)throws SQLException {
        long id;
        try(PreparedStatement s=c.prepareStatement(INSERT_EVENT,Statement.RETURN_GENERATED_KEYS)) {
            s.setQueryTimeout(5);bind(s,b.id,generation,channel.name(),operation.name(),b.version,content,decision,cycle,state.name(),actor,kind,time.replace('T',' '));one(s.executeUpdate());
            try(ResultSet r=s.getGeneratedKeys()) {
                if(r.getMetaData().getColumnCount()!=1||!r.next())throw integrity();id=positive(exact(r.getObject(1)));if(r.next())throw integrity();
            }
        }
        if(id<=after)throw integrity();
        Event read=event(c,E+"FROM medical_review_event FORCE INDEX (PRIMARY) WHERE id=? LIMIT 2",id);
        if(read==null||read.id!=id||read.record!=b.id||!generation.equals(read.generation)||read.channel!=channel||read.operation!=operation||!Objects.equals(read.before,b.version)||read.content!=content||read.decision!=decision||read.cycle!=cycle||read.state!=state||read.actor!=actor||!kind.equals(read.kind)||!time.equals(read.time))throw integrity();
        return read;
    }
    static final class Retirement {
        final CurrentPair pair; final List<Event> events;
        Retirement(CurrentPair pair,List<Event> events){this.pair=pair;this.events=Collections.unmodifiableList(new ArrayList<>(events));}
    }
    Retirement retire(Connection c,BindingObservation b,Proof proof,long actor,String kind,String time)throws SQLException {
        CurrentPair result=proof.pair;List<Event> events=new ArrayList<>();long after=Math.max(proof.watermarkId(),proof.pair.maxHead());
        // The intermediate pair deliberately has mixed content versions. Validate only after BOTH CAS updates.
        for(Channel channel:new Channel[]{Channel.DOCTOR,Channel.SOURCE}) {
            CurrentState old=proof.pair.selected(channel);if(old==null)continue;
            Event event=append(c,b,old.generation,channel,Operation.CONTENT_CHANGED,b.version+1,old.decision+1,old.cycle,State.SUPERSEDED,actor,kind,time,after);
            update(c,UPDATE_STATE,b.version+1,old.decision+1,old.cycle,State.SUPERSEDED.name(),event.id,null,old.record,old.channel.name(),old.generation,old.patient,old.owner,old.content,old.decision,old.cycle,old.state.name(),old.head,old.pointer);
            result=result.replace(new CurrentState(b.id,old.channel,old.generation,b.patient,b.owner,b.version+1,old.decision+1,old.cycle,State.SUPERSEDED,event.id,null));events.add(event);after=event.id;
        }
        return new Retirement(result,events);
    }
    Event closeGeneration(Connection c,BindingObservation b,Proof proof,long actor,String kind,String time)throws SQLException {
        if(proof.pair.size()==0)return null;
        if(closure(c,b.id,proof.pair.generation())!=null)throw integrity();
        return append(c,b,proof.pair.generation(),Channel.RECORD,Operation.SOURCE_DELETED,b.version,0,0,State.DELETED,actor,kind,time,Math.max(proof.watermarkId(),proof.pair.maxHead()));
    }
    void requireEvents(Connection c,List<Event> events)throws SQLException {
        for(Event expected:events) {
            sameEvent(expected,event(c,E+"FROM medical_review_event FORCE INDEX (PRIMARY) WHERE id=? LIMIT 2",expected.id));
            absent(c,"SELECT event_id,record_id FROM medical_review_text FORCE INDEX (PRIMARY) WHERE event_id=? LIMIT 1",expected.id);
            absent(c,"SELECT event_id,record_id,channel FROM medical_review_command FORCE INDEX (mrv57_command_event) WHERE event_id=? LIMIT 1",expected.id);
        }
    }
    static final class Counts {
        final long commands,texts,states,attachments,items;
        Counts(long commands,long texts,long states,long attachments,long items){this.commands=commands;this.texts=texts;this.states=states;this.attachments=attachments;this.items=items;}
    }
    Counts counts(Connection c,long record,Proof proof,boolean ready)throws SQLException {
        long commands=ready?count(c,"SELECT COUNT(*) FROM medical_review_command FORCE INDEX (mrv57_command_source) WHERE record_id=?",record):0;
        long texts=ready?count(c,"SELECT COUNT(*) FROM medical_review_text FORCE INDEX (mrv57_text_source) WHERE record_id=?",record):0;
        return new Counts(commands,texts,ready?proof.pair.size():0,
                count(c,"SELECT COUNT(*) FROM medical_record_attachment WHERE record_id=?",record),
                count(c,"SELECT COUNT(*) FROM medical_record_item FORCE INDEX (idx_item_record) WHERE record_id=?",record));
    }
    void erase(Connection c,long record,Counts counts)throws SQLException {
        largeDelete(c,"DELETE FROM medical_review_command WHERE record_id=?",record,counts.commands);
        largeDelete(c,"DELETE FROM medical_review_text WHERE record_id=?",record,counts.texts);
        largeDelete(c,"DELETE FROM medical_review_state WHERE record_id=?",record,counts.states);
    }
    void requirePayloadAbsent(Connection c,long record)throws SQLException {
        absent(c,"SELECT event_id,record_id FROM medical_review_text FORCE INDEX (mrv57_text_source) WHERE record_id=? LIMIT 1",record);
        absent(c,"SELECT record_id,channel,event_id FROM medical_review_command FORCE INDEX (mrv57_command_source) WHERE record_id=? LIMIT 1",record);
    }
    void requireChildrenAbsent(Connection c,long record)throws SQLException {
        absent(c,"SELECT id,record_id FROM medical_record_attachment WHERE record_id=? LIMIT 1",record);
        absent(c,"SELECT id,record_id FROM medical_record_item FORCE INDEX (idx_item_record) WHERE record_id=? LIMIT 1",record);
    }
    void requireReset(Connection c,long record)throws SQLException {
        query(c,"SELECT verification_status,verified_by,verified_at,verification_note IS NULL AS note_is_null FROM medical_record WHERE id=? LIMIT 2",r->{
            if(!r.next()||!"REVIEW_REQUIRED".equals(r.getString("verification_status"))||r.getObject("verified_by")!=null||r.getObject("verified_at")!=null||!Long.valueOf(1).equals(number(r,"note_is_null"))||r.next())throw integrity();return null;
        },record);
    }
    private void absent(Connection c,String sql,long key)throws SQLException {
        query(c,sql,r->{if(r.next())throw integrity();return null;},key);
    }
    private long count(Connection c,String sql,long record)throws SQLException {
        return query(c,sql,r->{if(r.getMetaData().getColumnCount()!=1||!r.next())throw integrity();Long n=exact(r.getObject(1));if(n==null||n<0||r.next())throw integrity();return n;},record);
    }
    private void largeDelete(Connection c,String sql,long record,long expected)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)){s.setQueryTimeout(5);bind(s,record);long count;try{count=s.executeLargeUpdate();}catch(UnsupportedOperationException unsupported){throw MedicalReviewSchema.notReady();}if(count<0||count!=expected)throw integrity();}
    }
    static WorkflowException integrity(){return new WorkflowException(409,"MEDICAL_REVIEW_INTEGRITY","The medical review lifecycle is inconsistent. Reload and request a review.");}
    static WorkflowException conflict(String suffix,String message){return new WorkflowException(409,"MEDICAL_REVIEW_"+suffix,message);}
    static WorkflowException exhausted(){return conflict("VERSION_EXHAUSTED","This review version cannot be advanced.");}
    private static String wallClock(String value){if(value==null)throw integrity();MedicalReviewContentFingerprint.time(value);if(value.compareTo("1000-01-01T00:00:00.000000")<0)throw integrity();return value;}
    private static long positive(Long value){if(value==null||value<1||value>MAX_ID)throw integrity();return value;}
    static Long exact(Object value){
        if(value==null)return null;
        try {
            if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)return ((Number)value).longValue();
            if(value instanceof BigInteger)return ((BigInteger)value).longValueExact();
            if(value instanceof BigDecimal)return ((BigDecimal)value).longValueExact();
        }catch(ArithmeticException failure){throw integrity();}
        throw integrity();
    }
    private static Long number(ResultSet r,String column)throws SQLException{Object value=r.getObject(column);if(("occurred_at_null".equals(column)||"note_is_null".equals(column))&&value instanceof Boolean)return Boolean.TRUE.equals(value)?1L:0L;return exact(value);}
    private interface Rows<T>{T read(ResultSet r)throws SQLException;}
    private static <T>T query(Connection c,String sql,Rows<T> rows,Object...args)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){s.setQueryTimeout(5);bind(s,args);try(ResultSet r=s.executeQuery()){return rows.read(r);}}}
    private static void update(Connection c,String sql,Object...args)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){s.setQueryTimeout(5);bind(s,args);one(s.executeUpdate());}}
    private static void bind(PreparedStatement s,Object...args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    private static void one(int count){if(count!=1)throw integrity();}
}
