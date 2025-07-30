package com.pmd_failure_bot.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "pmd_reports")
public class PmdReport {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "file_path")
    private String filePath;
    
    @Column(name = "executor_kerberos_id")
    private String executorKerberosId;
    
    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;
    
    @Column(name = "report_id")
    private String reportId;
    
    @Column(name = "step_name", nullable = false)
    private String stepName;
    
    @Column(name = "worker_process_group_id")
    private String workerProcessGroupId;
    
    @Column(name = "hostname")
    private String hostname;
    
    @Column(name = "requesting_kerberos_id")
    private String requestingKerberosId;
    
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getExecutorKerberosId() {
        return executorKerberosId;
    }

    public void setExecutorKerberosId(String executorKerberosId) {
        this.executorKerberosId = executorKerberosId;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public String getWorkerProcessGroupId() {
        return workerProcessGroupId;
    }

    public void setWorkerProcessGroupId(String workerProcessGroupId) {
        this.workerProcessGroupId = workerProcessGroupId;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getRequestingKerberosId() {
        return requestingKerberosId;
    }

    public void setRequestingKerberosId(String requestingKerberosId) {
        this.requestingKerberosId = requestingKerberosId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
} 