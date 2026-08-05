package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.user.User;
import jakarta.persistence.*;

import java.time.Instant;

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
