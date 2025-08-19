package com.pmd_failure_bot.data.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;

@Getter
@Setter
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

    @Column(name = "attachment_id", unique = true)
    private String attachmentId;

    @Column(name = "datacenter")
    private String datacenter;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "content")
    private byte[] content;

    @Column(name = "report_date")
    private LocalDate reportDate;


    public String getContent() {
        if (content == null || content.length == 0) return "";
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(content);
            try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bais)) {
                return new String(gis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            try {
                return new String(content, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    public void setContent(String contentString) {
        if (contentString == null || contentString.isEmpty()) {
            this.content = new byte[0];
            return;
        }
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(baos)) {
                gos.write(contentString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            this.content = baos.toByteArray();
        } catch (Exception e) {
            this.content = contentString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}


