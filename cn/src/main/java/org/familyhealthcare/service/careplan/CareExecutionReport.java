package org.familyhealthcare.service.careplan;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** An immutable, minimum-data projection. No entity, authorization object, or mutable public map is exposed. */
public final class CareExecutionReport {
    private final Patient patient;
    private final Scope scope;
    private final Metadata metadata;
    private final CurrentSummary currentSummary;
    private final List<CurrentAction> currentActions;
    private final List<CurrentAction> currentAttention;
    private final ActivitySummary activitySummary;
    private final List<PeriodEvent> periodEvents;
    private final String questionsAvailability;
    private final List<Question> questions;
    public CareExecutionReport(Patient patient, Scope scope, Metadata metadata, CurrentSummary currentSummary, List<CurrentAction> currentActions, List<CurrentAction> currentAttention, ActivitySummary activitySummary, List<PeriodEvent> periodEvents, String questionsAvailability, List<Question> questions) {
        if (!Arrays.asList("AVAILABLE", "NOT_AUTHORIZED", "NOT_INCLUDED_IN_PLAN_SCOPE").contains(questionsAvailability)) throw new IllegalArgumentException("Invalid question availability.");
        this.patient = Objects.requireNonNull(patient, "patient");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.currentSummary = currentSummary;
        this.currentActions = copy(currentActions);
        this.currentAttention = copy(currentAttention);
        this.activitySummary = activitySummary;
        this.periodEvents = copy(periodEvents);
        this.questionsAvailability = questionsAvailability;
        this.questions = copy(questions);
    }
    public int getReportSchemaVersion() { return 1; }
    public String getCompleteness() { return "COMPLETE"; }
    public Patient getPatient() { return patient; }
    public Scope getScope() { return scope; }
    public Metadata getMetadata() { return metadata; }
    public CurrentSummary getCurrentSummary() { return currentSummary; }
    public List<CurrentAction> getCurrentActions() { return currentActions; }
    public List<CurrentAction> getCurrentAttention() { return currentAttention; }
    public ActivitySummary getActivitySummary() { return activitySummary; }
    public List<PeriodEvent> getPeriodEvents() { return periodEvents; }
    public String getQuestionsAvailability() { return questionsAvailability; }
    public List<Question> getQuestions() { return questions; }
    private static <T> List<T> copy(List<T> values) {
        if (values == null) return Collections.emptyList();
        List<T> result = new ArrayList<>(values);
        for (T value : result) Objects.requireNonNull(value, "report list entry");
        return Collections.unmodifiableList(result);
    }

    public static final class Patient {
        private final long id;
        private final String displayName;
        public Patient(long id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
        public long getId() { return id; }
        public String getDisplayName() { return displayName; }
    }

    public static final class Scope {
        private final Long planId;
        private final String label;
        public Scope(Long planId, String label) {
            this.planId = planId;
            this.label = label;
            if (!(planId == null ? "ALL_PLANS" : "SINGLE_PLAN").equals(label)) throw new IllegalArgumentException("Invalid report scope.");
        }
        public Long getPlanId() { return planId; }
        public String getLabel() { return label; }
    }

    public static final class Metadata {
        private final LocalDate fromDate;
        private final LocalDate toDate;
        private final String timeZone;
        private final String language;
        private final Instant rangeStartAt;
        private final Instant rangeEndExclusiveAt;
        private final Instant currentAsOf;
        private final Instant generatedAt;
        public Metadata(CareExecutionReportContracts.Request request, Instant currentAsOf, Instant generatedAt) {
            this.fromDate = request.getFromDate();
            this.toDate = request.getToDate();
            this.timeZone = request.getTimeZone().getId();
            this.language = request.getLanguage();
            this.rangeStartAt = request.getRangeStartAt();
            this.rangeEndExclusiveAt = request.getRangeEndExclusiveAt();
            this.currentAsOf = Objects.requireNonNull(currentAsOf, "currentAsOf");
            this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public LocalDate getFromDate() { return fromDate; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public LocalDate getToDate() { return toDate; }
        public String getTimeZone() { return timeZone; }
        public String getLanguage() { return language; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getRangeStartAt() { return rangeStartAt; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getRangeEndExclusiveAt() { return rangeEndExclusiveAt; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getCurrentAsOf() { return currentAsOf; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getGeneratedAt() { return generatedAt; }
    }

    public static final class CurrentSummary {
        private final long total;
        private final long open;
        private final long needsHelp;
        private final long submitted;
        private final long confirmed;
        private final long overdue;
        private final long needsSupplement;
        public CurrentSummary(long open, long needsHelp, long submitted, long confirmed, long overdue, long needsSupplement) {
            if (open < 0 || needsHelp < 0 || submitted < 0 || confirmed < 0 || overdue < 0 || needsSupplement < 0) throw new IllegalArgumentException("Negative report count.");
            this.total = Math.addExact(Math.addExact(open, needsHelp), Math.addExact(submitted, confirmed));
            if (overdue > Math.addExact(open, needsHelp) || needsSupplement > open) throw new IllegalArgumentException("Invalid current subset count.");
            this.open = open;
            this.needsHelp = needsHelp;
            this.submitted = submitted;
            this.confirmed = confirmed;
            this.overdue = overdue;
            this.needsSupplement = needsSupplement;
        }
        public long getTotal() { return total; }
        public long getOpen() { return open; }
        public long getNeedsHelp() { return needsHelp; }
        public long getSubmitted() { return submitted; }
        public long getConfirmed() { return confirmed; }
        public long getOverdue() { return overdue; }
        public long getNeedsSupplement() { return needsSupplement; }
    }

    public static final class ActivitySummary {
        private final long eventCount;
        private final long distinctActionCount;
        private final Map<String, Long> eventTypeCounts;
        public ActivitySummary(long eventCount, long distinctActionCount, Map<String, Long> eventTypeCounts) {
            if (eventCount < 0 || distinctActionCount < 0 || distinctActionCount > eventCount) throw new IllegalArgumentException("Invalid activity count.");
            Map<String, Long> counts = new LinkedHashMap<>(Objects.requireNonNull(eventTypeCounts, "eventTypeCounts"));
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                if (!CareExecutionReportContracts.PUBLIC_EVENT_TYPES.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0) throw new IllegalArgumentException("Invalid public event count.");
            }
            this.eventCount = eventCount; this.distinctActionCount = distinctActionCount;
            this.eventTypeCounts = Collections.unmodifiableMap(counts);
        }
        public long getEventCount() { return eventCount; }
        public long getDistinctActionCount() { return distinctActionCount; }
        public Map<String, Long> getEventTypeCounts() { return eventTypeCounts; }
    }

    public static final class CurrentAction {
        private final long planId;
        private final long revisionId;
        private final long actionId;
        private final long assignedUserId;
        private final int revisionNo;
        private final String planTitle;
        private final String instructions;
        private final String instruction;
        private final String status;
        private final Instant dueAt;
        private final Instant reviewWaitingSince;
        private final boolean overdue;
        private final boolean needsSupplement;
        private final boolean assigneeAvailable;
        private final EventSummary latestReceipt;
        private final EventSummary latestReturn;
        private final EventSummary latestReview;
        private final EventSummary latestHelp;
        private final EventSummary latestFollowUp;
        private final List<Evidence> evidence;
        public CurrentAction(long planId, long revisionId, long actionId, long assignedUserId, int revisionNo, String planTitle, String instructions, String instruction, String status, Instant dueAt, Instant reviewWaitingSince, boolean overdue, boolean needsSupplement, boolean assigneeAvailable, EventSummary latestReceipt, EventSummary latestReturn, EventSummary latestReview, EventSummary latestHelp, EventSummary latestFollowUp, List<Evidence> evidence) {
            this.planId = planId;
            this.revisionId = revisionId;
            this.actionId = actionId;
            this.assignedUserId = assignedUserId;
            this.revisionNo = revisionNo;
            this.planTitle = planTitle;
            this.instructions = instructions;
            this.instruction = instruction;
            this.status = status;
            this.dueAt = dueAt;
            this.reviewWaitingSince = reviewWaitingSince;
            this.overdue = overdue;
            this.needsSupplement = needsSupplement;
            this.assigneeAvailable = assigneeAvailable;
            this.latestReceipt = latestReceipt;
            this.latestReturn = latestReturn;
            this.latestReview = latestReview;
            this.latestHelp = latestHelp;
            this.latestFollowUp = latestFollowUp;
            this.evidence = copy(evidence);
        }
        public long getPlanId() { return planId; }
        public long getRevisionId() { return revisionId; }
        public long getActionId() { return actionId; }
        public long getAssignedUserId() { return assignedUserId; }
        public int getRevisionNo() { return revisionNo; }
        public String getPlanTitle() { return planTitle; }
        public String getInstructions() { return instructions; }
        public String getInstruction() { return instruction; }
        public String getStatus() { return status; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getDueAt() { return dueAt; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getReviewWaitingSince() { return reviewWaitingSince; }
        public boolean isOverdue() { return overdue; }
        public boolean isNeedsSupplement() { return needsSupplement; }
        public boolean isAssigneeAvailable() { return assigneeAvailable; }
        public EventSummary getLatestReceipt() { return latestReceipt; }
        public EventSummary getLatestReturn() { return latestReturn; }
        public EventSummary getLatestReview() { return latestReview; }
        public EventSummary getLatestHelp() { return latestHelp; }
        public EventSummary getLatestFollowUp() { return latestFollowUp; }
        public List<Evidence> getEvidence() { return evidence; }
    }

    public static final class EventSummary {
        private final long eventId;
        private final long actorId;
        private final String eventType;
        private final String actorName;
        private final String actorRole;
        private final String actorRelation;
        private final String entryMode;
        private final String note;
        private final String followUpKind;
        private final Instant recordedAt;
        private final Instant occurredAt;
        private final List<Evidence> evidence;
        public EventSummary(long eventId, long actorId, String eventType, String actorName, String actorRole, String actorRelation, String entryMode, String note, String followUpKind, Instant recordedAt, Instant occurredAt, List<Evidence> evidence) {
            this.eventId = eventId;
            this.actorId = actorId;
            this.eventType = eventType;
            this.actorName = actorName;
            this.actorRole = actorRole;
            this.actorRelation = actorRelation;
            this.entryMode = entryMode;
            this.note = note;
            this.followUpKind = followUpKind;
            this.recordedAt = recordedAt;
            this.occurredAt = occurredAt;
            this.evidence = copy(evidence);
        }
        public long getEventId() { return eventId; }
        public long getActorId() { return actorId; }
        public String getEventType() { return eventType; }
        public String getActorName() { return actorName; }
        public String getActorRole() { return actorRole; }
        public String getActorRelation() { return actorRelation; }
        public String getEntryMode() { return entryMode; }
        public String getNote() { return note; }
        public String getFollowUpKind() { return followUpKind; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getRecordedAt() { return recordedAt; }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getOccurredAt() { return occurredAt; }
        public List<Evidence> getEvidence() { return evidence; }
    }

    public static final class PeriodEvent {
        private final EventSummary event;
        private final long planId;
        private final long revisionId;
        private final Long actionId;
        private final int revisionNo;
        private final String planTitle;
        private final String instructions;
        private final String actionInstruction;
        private final String actionStatusAfterEvent;
        private final String planLifecycleAtGeneration;
        private final boolean revisionIsCurrentAtGeneration;
        public PeriodEvent(EventSummary event, long planId, long revisionId, Long actionId, int revisionNo, String planTitle, String instructions, String actionInstruction, String actionStatusAfterEvent, String planLifecycleAtGeneration, boolean revisionIsCurrentAtGeneration) {
            this.event = Objects.requireNonNull(event, "event");
            this.planId = planId;
            this.revisionId = revisionId;
            this.actionId = actionId;
            this.revisionNo = revisionNo;
            this.planTitle = planTitle;
            this.instructions = instructions;
            this.actionInstruction = actionInstruction;
            this.actionStatusAfterEvent = actionStatusAfterEvent;
            this.planLifecycleAtGeneration = planLifecycleAtGeneration;
            this.revisionIsCurrentAtGeneration = revisionIsCurrentAtGeneration;
        }
        public long getEventId() { return event.getEventId(); }
        public long getActorId() { return event.getActorId(); }
        public String getEventType() { return event.getEventType(); }
        public String getActorName() { return event.getActorName(); }
        public String getActorRole() { return event.getActorRole(); }
        public String getActorRelation() { return event.getActorRelation(); }
        public String getEntryMode() { return event.getEntryMode(); }
        public String getNote() { return event.getNote(); }
        public String getFollowUpKind() { return event.getFollowUpKind(); }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getRecordedAt() { return event.getRecordedAt(); }
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant getOccurredAt() { return event.getOccurredAt(); }
        public List<Evidence> getEvidence() { return event.getEvidence(); }
        public long getPlanId() { return planId; }
        public long getRevisionId() { return revisionId; }
        public Long getActionId() { return actionId; }
        public int getRevisionNo() { return revisionNo; }
        public String getPlanTitle() { return planTitle; }
        public String getInstructions() { return instructions; }
        public String getActionInstruction() { return actionInstruction; }
        public String getActionStatusAfterEvent() { return actionStatusAfterEvent; }
        public String getPlanLifecycleAtGeneration() { return planLifecycleAtGeneration; }
        public boolean isRevisionIsCurrentAtGeneration() { return revisionIsCurrentAtGeneration; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Evidence {
        private final boolean restricted;
        private final String sourceType;
        private final Long sourceId;
        private final String title;
        private final String detailPath;
        public Evidence(boolean restricted, String sourceType, Long sourceId, String title, String detailPath) {
            this.restricted = restricted;
            this.sourceType = restricted ? null : sourceType;
            this.sourceId = restricted ? null : sourceId;
            this.title = restricted ? null : title;
            this.detailPath = restricted ? null : detailPath;
        }
        public boolean isRestricted() { return restricted; }
        public String getSourceType() { return sourceType; }
        public Long getSourceId() { return sourceId; }
        public String getTitle() { return title; }
        public String getDetailPath() { return detailPath; }
    }

    public static final class Question {
        private final long id;
        private final String title;
        private final String status;
        private final String description;
        private final String answer;
        private final String followUp;
        private final String actorName;
        private final Long actorId;
        private final String createdAtLocal;
        private final String updatedAtLocal;
        private final String eventAtLocal;
        public Question(long id, String title, String status, String description, String answer, String followUp, String actorName, Long actorId, String createdAtLocal, String updatedAtLocal, String eventAtLocal) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.description = description;
            this.answer = answer;
            this.followUp = followUp;
            this.actorName = actorName;
            this.actorId = actorId;
            this.createdAtLocal = createdAtLocal;
            this.updatedAtLocal = updatedAtLocal;
            this.eventAtLocal = eventAtLocal;
        }
        public long getId() { return id; }
        public String getTitle() { return title; }
        public String getStatus() { return status; }
        public String getDescription() { return description; }
        public String getAnswer() { return answer; }
        public String getFollowUp() { return followUp; }
        public String getActorName() { return actorName; }
        public Long getActorId() { return actorId; }
        public String getCreatedAtLocal() { return createdAtLocal; }
        public String getUpdatedAtLocal() { return updatedAtLocal; }
        public String getEventAtLocal() { return eventAtLocal; }
        public String getTimeBasis() { return "LEGACY_UNZONED"; }
    }
}
