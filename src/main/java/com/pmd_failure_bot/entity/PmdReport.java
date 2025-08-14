package com.pmd_failure_bot.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "pmd_failure_logs")
public class PmdReport {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "record_id")
    private String recordId;
    
    @Column(name = "work_id")
    private String workId;
    
    @Column(name = "case_number")
    private Integer caseNumber;
    
    @Column(name = "step_name")
    private String stepName;
    
    @Column(name = "attachment_id")
    private String attachmentId;
    
    @Column(name = "datacenter")
    private String datacenter;
    
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;
    
    @Column(name = "report_date")
    private LocalDate reportDate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getWorkId() {
        return workId;
    }

    public void setWorkId(String workId) {
        this.workId = workId;
    }

    public Integer getCaseNumber() {
        return caseNumber;
    }

    public void setCaseNumber(Integer caseNumber) {
        this.caseNumber = caseNumber;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public String getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(String attachmentId) {
        this.attachmentId = attachmentId;
    }

    public String getDatacenter() {
        return datacenter;
    }

    public void setDatacenter(String datacenter) {
        this.datacenter = datacenter;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }
} 