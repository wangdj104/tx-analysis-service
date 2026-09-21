package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * imagingReportAttachmententity
 */
@Data
@TableName("medical_record_attachment")
@ApiModel("imagingReportAttachment")
public class MedicalRecordAttachment {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("relatedrecordID")
    private Long recordId;

    @ApiModelProperty("filePath")
    private String filePath;

    @ApiModelProperty("originalfilename")
    private String fileName;

    @ApiModelProperty("filetype: IMAGE, PDF")
    private String fileType;

    @ApiModelProperty("filelargesmall(bytes)")
    private Long fileSize;

    @ApiModelProperty("fileBase64content")
    private String fileContent;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}