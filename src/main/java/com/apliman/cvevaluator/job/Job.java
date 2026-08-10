package com.apliman.cvevaluator.job;
import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /**
     * TEXT, not the {@code varchar(255)} a bare {@code String} field gets.
     * {@code CreateJobRequest} has always allowed 5000 characters here, so the
     * default column was 255 characters narrower than the only validation
     * guarding it — every fixture job overflows it, and the insert failed at
     * flush with a {@code DataIntegrityViolationException}. Widening the column
     * is the fix that matches the DTO rather than the DTO being quietly wrong.
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * The authored requirements, in the order the recruiter wrote them.
     *
     * <p>Stored as {@code jsonb} on this row, not as a child entity — see
     * {@link JobRequirement} for why.
     *
     * <p>The column is {@code requirements_json} rather than {@code requirements}
     * because a free-text {@code varchar} column of that name already exists on
     * this table, left behind by the prose requirements blob this list replaced.
     * {@code ddl-auto=update} never alters an existing column's type, so pointing
     * this at {@code requirements} would fail at the first insert with a type
     * mismatch rather than at startup. The old column is now unmapped; it still
     * holds the prose for postings created before the switch, which is the only
     * reason it has not been dropped.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requirements_json", columnDefinition = "jsonb")
    private List<JobRequirement> requirements;

    /**
     * Bumped every time the list is replaced. An evaluation records the version
     * it was made against, which is what makes a re-evaluation after an edit
     * comparable to the one before it instead of merely newer.
     *
     * <p><strong>The {@code default 1} is load-bearing and was learned the hard
     * way.</strong> Hibernate derives {@code not null} from the Java primitive on
     * its own — leaving the annotation off does not make the column nullable. So
     * {@code ddl-auto=update} emits
     * {@code alter table jobs add column requirements_version integer not null}
     * against a table that already has rows, PostgreSQL rejects it, and — this is
     * the part that bites — Hibernate logs the failure as a WARN and starts the
     * application anyway. The column is then simply absent, and every read and
     * write of {@code jobs} fails at runtime with "column does not exist" while
     * startup looks clean.
     *
     * <p>Naming the default makes the ALTER succeed and backfills existing
     * postings with version 1, which is also the semantically right answer: a job
     * whose requirements have never been edited is on its first version. With no
     * Flyway in this project, the column definition is the only place to say so.
     */
    @Column(name = "requirements_version", columnDefinition = "integer not null default 1")
    private int requirementsVersion;

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
            String seniority,
            List<JobRequirement> requirements,
            User createdByRecruiter
    ) {
        this.title = title;
        this.description = description;
        this.seniority = seniority;
        this.requirements = requirements == null ? List.of() : List.copyOf(requirements);
        this.requirementsVersion = 1;
        this.createdByRecruiter = createdByRecruiter;
        this.createdAt = Instant.now();
        this.active = true;
    }

    /**
     * Swaps the whole list and bumps the version.
     *
     * <p>Whole-list replacement is the only edit operation there is. Requirements
     * are not addressed individually anywhere — no {@code PATCH /requirements/R3}
     * — so an id is a label within a version, not a handle that outlives one.
     *
     * <p>The version is bumped unconditionally, including when the new list is
     * identical to the old one. Comparing first would be cheap, but a version
     * that only sometimes moves after a write is a worse thing to reason about
     * than a redundant re-evaluation, and it would make "the recruiter saved the
     * form" and "nothing happened" indistinguishable in the log.
     */
    public void replaceRequirements(List<JobRequirement> requirements) {
        this.requirements = List.copyOf(requirements);
        this.requirementsVersion++;
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

    /**
     * Never null, and never modified in place by callers. Hibernate hands back a
     * mutable list it deserialised from the column, so this wraps it — an
     * in-place {@code add} would be dirty-checked and silently persisted without
     * the version ever moving, which is exactly the drift
     * {@link #replaceRequirements} exists to prevent.
     */
    public List<JobRequirement> getRequirements() {
        return requirements == null ? List.of() : List.copyOf(requirements);
    }

    public int getRequirementsVersion() {
        return requirementsVersion;
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