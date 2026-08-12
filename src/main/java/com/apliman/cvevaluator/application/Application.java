package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.evaluation.Evaluation;
import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "applications")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private User candidate;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String contentType;

    private long sizeBytes;

    @Column(nullable = false)
    private String storagePath;

    @Column(columnDefinition = "TEXT")
    private String extractedText;

    @Column(columnDefinition = "TEXT")
    private String redactedText;

    private String extractionMethod;

    private int textLength;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    private String failureReason;

    @Column(nullable = false)
    private Instant submittedAt;

    /**
     * The evaluation history for this CV, newest first.
     *
     * <p>The inverse side. {@code Evaluation.application} owns the
     * {@code application_id} column; {@code mappedBy} says so and stops
     * Hibernate creating a second foreign key for the same relationship.
     *
     * <p><strong>LAZY, and reading it outside a transaction throws.</strong>
     * That is the trap worth knowing here: {@code getEvaluations()} on a
     * detached instance — one returned from a repository call that has already
     * committed — fails with {@code LazyInitializationException} rather than
     * returning an empty list. Callers outside a transaction should go through
     * {@code EvaluationRepository} instead, which is also the only way to ask
     * for just the latest one rather than dragging the whole history into
     * memory to look at its head.
     *
     * <p>{@code @OrderBy} rather than leaving the order to the database, so the
     * head of this list means the same thing as the head of the repository's
     * newest-first query. An unordered collection whose first element is
     * <em>usually</em> the latest is worse than one that is never ordered at
     * all, because it works in testing.
     *
     * <p>No cascade and no {@code orphanRemoval} on purpose. Deleting an
     * {@code Application} does not currently happen anywhere, and wiring
     * removal through this collection would mean an accidental
     * {@code list.remove(...)} silently deletes a row. Deletion goes through
     * the repository, where it is explicit.
     */
    @OneToMany(mappedBy = "application", fetch = FetchType.LAZY)
    @OrderBy("createdAt DESC")
    private List<Evaluation> evaluations;

    protected Application() {
    }

    public Application(
            Job job,
            User candidate,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String storagePath
    ) {
        this.job = job;
        this.candidate = candidate;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storagePath = storagePath;
        this.status = ApplicationStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public User getCandidate() {
        return candidate;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public String getRedactedText() {
        return redactedText;
    }

    public String getExtractionMethod() {
        return extractionMethod;
    }

    public int getTextLength() {
        return textLength;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    /**
     * Newest first. Never null — an application with no evaluations yet reads
     * as an empty list rather than forcing every caller to null-check.
     *
     * @throws org.hibernate.LazyInitializationException if called on a detached
     *         instance; see the field.
     */
    public List<Evaluation> getEvaluations() {
        return evaluations == null ? List.of() : List.copyOf(evaluations);
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public void setRedactedText(String redactedText) {
        this.redactedText = redactedText;
    }

    public void setExtractionMethod(String extractionMethod) {
        this.extractionMethod = extractionMethod;
    }

    public void setTextLength(int textLength) {
        this.textLength = textLength;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
