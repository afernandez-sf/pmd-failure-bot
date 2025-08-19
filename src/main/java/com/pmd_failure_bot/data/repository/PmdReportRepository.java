package com.pmd_failure_bot.data.repository;

import com.pmd_failure_bot.data.entity.PmdReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PmdReportRepository extends JpaRepository<PmdReport, Long> {

    boolean existsByAttachmentId(String attachmentId);

    @Query("SELECT p.attachmentId FROM PmdReport p WHERE p.attachmentId IN :attachmentIds")
    List<String> findExistingAttachmentIds(@Param("attachmentIds") List<String> attachmentIds);
}


