package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * One stored evaluation of one application.
 *
 * <h2>Never modified, and kept for a bounded window</h2>
 *
 * Nothing ever updates a row in this table — there are no setters, so there is
 * nothing for Hibernate's dirty checking to notice. A re-evaluation after a
 * requirements edit inserts another row against the same application, and the
 * old one stays exactly as it was written. That is what makes "the requirements
 * change is what moved this candidate" a claim you can check rather than
 * assert: both evaluations are there, each stamped with the
 * {@code requirementsVersion} and {@code promptVersion} it was made under.
 *
 * <p>The history is capped rather than infinite. Beyond
 * {@code cvevaluator.evaluation.max-per-application} the oldest rows are
 * removed by {@link EvaluationService#record} — the comparison above only ever
 * needed recent history, and every row here carries two {@code jsonb} blobs, so
 * an unbounded one grows on every requirements edit forever for rows nobody
 * reads. A row is therefore either present exactly as written, or gone; it is
 * never something in between.
 *
 * <p>There is deliberately no {@code current} flag and no unique constraint on
 * {@code application_id}. "The latest evaluation" is a query — order by
 * {@code createdAt} — not a column that two concurrent writers can disagree
 * about.
 *
 * <h2>Assessments as jsonb</h2>
 *
 * Same call as {@code Job.requirements}, and for the same reason: an assessment
 * has no lifecycle of its own. It is never addressed individually, never
 * referenced from another table, and is only ever read as part of the whole
 * evaluation. The alternative — an {@code evaluation_assessments} child table —
 * buys per-row addressing that nothing uses and costs a join on every read.
 *
 * <p>The cost is real and worth naming: assessments are not queryable in SQL
 * without jsonb operators, so "how many CVs missed R3" is not a
 * {@code GROUP BY}. If that query is ever needed, this is the decision to
 * revisit.
 */
@Entity
@Table(name = "evaluations")
public class Evaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assessments_json", columnDefinition = "jsonb")
    private List<RequirementAssessment> assessments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimension_scores_json", columnDefinition = "jsonb")
    private List<DimensionScore> dimensionScores;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Verdict verdict;

    @Column(columnDefinition = "TEXT")
    private String summary;

    /** Which rubric produced this. See {@code AssessmentRubric}. */
    @Column(nullable = false)
    private String promptVersion;

    /** The job's {@code requirementsVersion} at the moment of the call. */
    private int requirementsVersion;

    private int tokensIn;

    private int tokensOut;

    private long latencyMs;

    @Column(nullable = false)
    private Instant createdAt;

    protected Evaluation() {
    }

    /**
     * Built from a completed {@link EvaluationResult}, never field by field.
     * There is no setter on this class and no partial constructor: an evaluation
     * that exists is an evaluation that was validated, and the only way to get an
     * {@code EvaluationResult} is through {@code LlmEvaluator}, which validates.
     *
     * <p>Construct through {@link EvaluationService#record} rather than calling
     * this and saving directly — that is where the retention cap is applied, and
     * a bare {@code repository.save} bypasses it.
     */
    public Evaluation(Application application, EvaluationResult result) {
        this.application = application;
        this.assessments = List.copyOf(result.assessments());
        this.dimensionScores = List.copyOf(result.dimensionScores());
        this.verdict = result.verdict();
        this.summary = result.summary();
        this.promptVersion = result.promptVersion();
        this.requirementsVersion = result.evaluatedAgainstRequirementsVersion();
        this.tokensIn = result.tokensIn();
        this.tokensOut = result.tokensOut();
        this.latencyMs = result.latencyMs();
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Application getApplication() {
        return application;
    }

    /** Never null, never the stored instance — see {@code Job.getRequirements}. */
    public List<RequirementAssessment> getAssessments() {
        return assessments == null ? List.of() : List.copyOf(assessments);
    }

    public List<DimensionScore> getDimensionScores() {
        return dimensionScores == null ? List.of() : List.copyOf(dimensionScores);
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public String getSummary() {
        return summary;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public int getRequirementsVersion() {
        return requirementsVersion;
    }

    public int getTokensIn() {
        return tokensIn;
    }

    public int getTokensOut() {
        return tokensOut;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
