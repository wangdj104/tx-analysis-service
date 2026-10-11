package org.familyhealthcare.entity;
import lombok.Data;
import java.time.LocalDateTime;
/** Durable external-operation journal. Tokens are never sent to the client. */
@Data
public class HealthAnalysisRun {
 private Long id;private Long automationId;private String claimToken;private String state;private Integer scheduled;
 private LocalDateTime scheduledFor;private String settingsJson;private LocalDateTime claimedAt;private LocalDateTime updatedAt;
 private Long analysisRecordId;private String lastError;private String notificationOutcomes;
}
