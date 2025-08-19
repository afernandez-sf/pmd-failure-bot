package com.pmd_failure_bot.data.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "step_names", uniqueConstraints = {@UniqueConstraint(columnNames = {"step_name"})})
public class StepName {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_name", nullable = false, length = 256)
    private String stepName;

    public StepName(String stepName) {
        this.stepName = stepName;
    }
}


