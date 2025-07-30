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
    
    @Query("SELECT p FROM PmdReport p WHERE " +
           "(:filePath IS NULL OR p.filePath = :filePath) AND " +
           "(:executorKerberosId IS NULL OR p.executorKerberosId = :executorKerberosId) AND " +
           "p.reportDate = :reportDate AND " +
           "(:reportId IS NULL OR p.reportId = :reportId) AND " +
           "p.stepName = :stepName AND " +
           "(:workerProcessGroupId IS NULL OR p.workerProcessGroupId = :workerProcessGroupId) AND " +
           "(:hostname IS NULL OR p.hostname = :hostname) AND " +
           "(:requestingKerberosId IS NULL OR p.requestingKerberosId = :requestingKerberosId)")
    List<PmdReport> findByFilters(
            @Param("filePath") String filePath,
            @Param("executorKerberosId") String executorKerberosId,
            @Param("reportDate") LocalDate reportDate,
            @Param("reportId") String reportId,
            @Param("stepName") String stepName,
            @Param("workerProcessGroupId") String workerProcessGroupId,
            @Param("hostname") String hostname,
            @Param("requestingKerberosId") String requestingKerberosId
    );
} 