package com.apliman.cvevaluator.job;
import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false)
    private String title;

    private String description;

    private String requirements;

    private String seniority;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="Created_by_recruiter",nullable=false)
    private User createdByRecruiter;

    private Instant createdAt;

    private boolean active;

    @OneToMany(mappedBy = "job", fetch = FetchType.LAZY)
    private List<Application> applications;


    protected Job() {
    }


    public Job(
            String title,
            String description,
            String requirements,
            String seniority,
            User createdByRecruiter
    ) {
        this.title = title;
        this.description = description;
        this.requirements = requirements;
        this.seniority = seniority;
        this.createdByRecruiter = createdByRecruiter;
        this.createdAt = Instant.now();
        this.active = true;
    }


    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getRequirements() {
        return requirements;
    }

    public String getSeniority() {
        return seniority;
    }

    public User getCreatedByRecruiter() {
        return createdByRecruiter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return active;
    }

    public List<Application> getApplications() {
        return applications;
    }
}