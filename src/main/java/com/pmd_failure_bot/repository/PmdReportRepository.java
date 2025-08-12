package com.pmd_failure_bot.repository;

import com.pmd_failure_bot.entity.PmdReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;


@Repository
public interface PmdReportRepository extends JpaRepository<PmdReport, Long> {
    
    // Method for queries WITHOUT date filter
    @Query("SELECT p FROM PmdReport p WHERE " +
           "(:recordId IS NULL OR p.recordId = :recordId) AND " +
           "(:workId IS NULL OR p.workId = :workId) AND " +
           "(:caseNumber IS NULL OR p.caseNumber = :caseNumber) AND " +
           "(:stepName IS NULL OR p.stepName LIKE :stepName) AND " +
           "(:attachmentId IS NULL OR p.attachmentId = :attachmentId) AND " +
           "(:hostname IS NULL OR p.hostname = :hostname)")
    List<PmdReport> findByFiltersWithoutDate(
            @Param("recordId") String recordId,
            @Param("workId") String workId,
            @Param("caseNumber") Integer caseNumber,
            @Param("stepName") String stepName,
            @Param("attachmentId") String attachmentId,
            @Param("hostname") String hostname
    );
    
    // Method for queries WITH date filter
    @Query("SELECT p FROM PmdReport p WHERE " +
           "(:recordId IS NULL OR p.recordId = :recordId) AND " +
           "(:workId IS NULL OR p.workId = :workId) AND " +
           "(:caseNumber IS NULL OR p.caseNumber = :caseNumber) AND " +
           "(:stepName IS NULL OR p.stepName LIKE :stepName) AND " +
           "(:attachmentId IS NULL OR p.attachmentId = :attachmentId) AND " +
           "(:hostname IS NULL OR p.hostname = :hostname) AND " +
           "p.reportDate = :reportDate")
    List<PmdReport> findByFiltersWithDate(
            @Param("recordId") String recordId,
            @Param("workId") String workId,
            @Param("caseNumber") Integer caseNumber,
            @Param("stepName") String stepName,
            @Param("attachmentId") String attachmentId,
            @Param("hostname") String hostname,
            @Param("reportDate") LocalDate reportDate
    );
} 