package com.pmd_failure_bot.repository;

import com.pmd_failure_bot.entity.PmdReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;


@Repository
public interface PmdReportRepository extends JpaRepository<PmdReport, Long> {

    @Query("SELECT p FROM PmdReport p WHERE " +
           "(:recordId IS NULL OR p.recordId = :recordId) AND " +
           "(:workId IS NULL OR p.workId = :workId) AND " +
           "(:caseNumber IS NULL OR p.caseNumber = :caseNumber) AND " +
           "(:stepName IS NULL OR p.stepName LIKE :stepName) AND " +
           "(:attachmentId IS NULL OR p.attachmentId = :attachmentId) AND " +
           "(:datacenter IS NULL OR p.datacenter = :datacenter)")
    List<PmdReport> findByFilters(
            @Param("recordId") String recordId,
            @Param("workId") String workId,
            @Param("caseNumber") Integer caseNumber,
            @Param("stepName") String stepName,
            @Param("attachmentId") String attachmentId,
            @Param("datacenter") String datacenter
    );

    List<PmdReport> findByAttachmentIdIn(List<String> attachmentIds);
}