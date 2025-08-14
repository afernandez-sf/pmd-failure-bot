package com.pmd_failure_bot.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "step_names", uniqueConstraints = {@UniqueConstraint(columnNames = {"step_name"})})
public class StepName {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_name", nullable = false, length = 256)
    private String stepName;

    public StepName() {}

    public StepName(String stepName) {
        this.stepName = stepName;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }
}


