package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.JobRequirement;
import com.apliman.cvevaluator.job.RequirementKind;
import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two read paths: the latest evaluation, and the full history.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EvaluationRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EvaluationRepository evaluations;

    @Test
    void findLatest_returnsTheNewestEvaluation() {
        Application application = persistedApplication();
        save(application, Verdict.NOT_A_FIT, 1, Instant.now().minusSeconds(900));
        save(application, Verdict.WEAK_FIT, 2, Instant.now().minusSeconds(600));
        save(application, Verdict.STRONG_FIT, 3, Instant.now().minusSeconds(60));
        entityManager.flush();
        entityManager.clear();

        Evaluation latest = evaluations
                .findFirstByApplicationOrderByCreatedAtDesc(application)
                .orElseThrow();

        assertThat(latest.getVerdict()).isEqualTo(Verdict.STRONG_FIT);
        assertThat(latest.getRequirementsVersion()).isEqualTo(3);
    }

    /**
     * A CV that has been extracted but never evaluated is the normal state right
     * after submission, so this must be an empty Optional rather than anything
     * a caller has to treat as a failure.
     */
    @Test
    void findLatest_isEmptyWhenTheApplicationHasNeverBeenEvaluated() {
        Application application = persistedApplication();
        entityManager.flush();
        entityManager.clear();

        assertThat(evaluations.findFirstByApplicationOrderByCreatedAtDesc(application)).isEmpty();
    }

    /** One application's history must never pick up another's rows. */
    @Test
    void findLatest_isScopedToOneApplication() {
        Application mine = persistedApplication();
        Application theirs = persistedApplication();
        save(mine, Verdict.WEAK_FIT, 1, Instant.now().minusSeconds(600));
        save(theirs, Verdict.STRONG_FIT, 1, Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertThat(evaluations.findFirstByApplicationOrderByCreatedAtDesc(mine).orElseThrow().getVerdict())
                .isEqualTo(Verdict.WEAK_FIT);
        assertThat(evaluations.findByApplicationOrderByCreatedAtDesc(mine)).hasSize(1);
    }

    @Test
    void findAll_returnsTheWholeHistoryNewestFirst() {
        Application application = persistedApplication();
        save(application, Verdict.NOT_A_FIT, 1, Instant.now().minusSeconds(900));
        save(application, Verdict.WEAK_FIT, 2, Instant.now().minusSeconds(600));
        save(application, Verdict.STRONG_FIT, 3, Instant.now().minusSeconds(60));
        entityManager.flush();
        entityManager.clear();

        assertThat(evaluations.findByApplicationOrderByCreatedAtDesc(application))
                .extracting(Evaluation::getRequirementsVersion)
                .containsExactly(3, 2, 1);
    }

    @Test
    void findAll_isEmptyWhenTheApplicationHasNeverBeenEvaluated() {
        Application application = persistedApplication();
        entityManager.flush();
        entityManager.clear();

        assertThat(evaluations.findByApplicationOrderByCreatedAtDesc(application)).isEmpty();
    }

    private Evaluation save(Application application, Verdict verdict, int version, Instant createdAt) {
        Evaluation evaluation = new Evaluation(application, result(verdict, version));
        ReflectionTestUtils.setField(evaluation, "createdAt", createdAt);
        return evaluations.save(evaluation);
    }

    private Application persistedApplication() {
        User recruiter = entityManager.persistAndFlush(
                new User("Recruiter", "hash", "r" + System.nanoTime() + "@example.com", Role.RECRUITER));
        User candidate = entityManager.persistAndFlush(
                new User("Candidate", "hash", "c" + System.nanoTime() + "@example.com", Role.CANDIDATE));
        Job job = entityManager.persistAndFlush(new Job("Backend Engineer", "desc", "Senior",
                List.of(new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE)),
                recruiter));

        return entityManager.persistAndFlush(new Application(
                job, candidate, "cv.pdf", "application/pdf", 1234L, "key.pdf"));
    }

    private static EvaluationResult result(Verdict verdict, int requirementsVersion) {
        return new EvaluationResult(
                List.of(new RequirementAssessment("R1", "5+ years of Java", RequirementKind.MUST_HAVE,
                        "Nine years stated.", RequirementStatus.MET, "nine years on the JVM")),
                List.of(
                        new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, "Owns outcomes.", 4, null),
                        new DimensionScore(ScoreDimension.COMMUNICATION_QUALITY, "Specific.", 5, null)),
                verdict,
                "A summary long enough to be a realistic stored value for this row.",
                "v2", requirementsVersion, 3000, 1500, 21000L);
    }
}
