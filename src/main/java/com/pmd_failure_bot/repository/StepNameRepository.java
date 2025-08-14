package com.pmd_failure_bot.repository;

import com.pmd_failure_bot.entity.StepName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StepNameRepository extends JpaRepository<StepName, Long> {
}


