package org.familyhealthcare.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.math.BigDecimal; import java.time.LocalDateTime;
@Data @TableName("patient_health_target")
public class PatientHealthTarget {
 @TableId(type=IdType.AUTO) private Long id; private Long userId; private Long patientId;
 private Integer systolicMin; private Integer systolicMax; private Integer diastolicMin; private Integer diastolicMax;
 private BigDecimal fastingGlucoseMin; private BigDecimal fastingGlucoseMax; private BigDecimal postmealGlucoseMin; private BigDecimal postmealGlucoseMax;
 private BigDecimal targetWeight; private BigDecimal weightGainLimit; private String emergencyContact; private String emergencyPhone;
 private String hospitalName; private String doctorName; private LocalDateTime createdAt; private LocalDateTime updatedAt;
}
