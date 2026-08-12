package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationRepository;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code Application} to {@code Evaluation} relationship, both directions.
 *
 * <p>Uses the real PostgreSQL schema rather than an in-memory substitute
 * because {@code Evaluation} stores its assessments as {@code jsonb}, which
 * only exists here. A test on H2 would pass against a column type the
 * application never uses.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EvaluationRelationshipTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EvaluationRepository evaluations;

    @Autowired
    private ApplicationRepository applications;

    @Test
    void anApplicationCanReachItsEvaluations() {
        Application application = persistedApplication();
        evaluations.save(new Evaluation(application, result(Verdict.WEAK_FIT, 1)));
        evaluations.save(new Evaluation(application, result(Verdict.STRONG_FIT, 2)));
        entityManager.flush();
        entityManager.clear();

        Application reloaded = applications.findById(application.getId()).orElseThrow();

        assertThat(reloaded.getEvaluations())
                .as("the inverse side resolves without a repository call")
                .hasSize(2);
    }

    /**
     * {@code @OrderBy} is what makes "the first one" mean "the latest one". Both
     * rows are written in the same transaction, so this also proves the ordering
     * comes from the annotation and not from insertion order happening to agree.
     */
    @Test
    void theEvaluationsArrivedNewestFirst() {
        Application application = persistedApplication();

        Evaluation older = new Evaluation(application, result(Verdict.WEAK_FIT, 1));
        setCreatedAt(older, Instant.now().minusSeconds(600));
        evaluations.save(older);

        Evaluation newer = new Evaluation(application, result(Verdict.STRONG_FIT, 2));
        setCreatedAt(newer, Instant.now());
        evaluations.save(newer);

        entityManager.flush();
        entityManager.clear();

        Application reloaded = applications.findById(application.getId()).orElseThrow();

        assertThat(reloaded.getEvaluations())
                .extracting(Evaluation::getVerdict)
                .containsExactly(Verdict.STRONG_FIT, Verdict.WEAK_FIT);
        assertThat(reloaded.getEvaluations())
                .extracting(Evaluation::getRequirementsVersion)
                .containsExactly(2, 1);
    }

    /** The owning side still works, and still points back at the same row. */
    @Test
    void anEvaluationStillReachesItsApplication() {
        Application application = persistedApplication();
        Evaluation saved = evaluations.save(new Evaluation(application, result(Verdict.STRONG_FIT, 1)));
        entityManager.flush();
        entityManager.clear();

        Evaluation reloaded = evaluations.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getApplication().getId()).isEqualTo(application.getId());
    }

    @Test
    void anApplicationWithNoEvaluationsReadsAsEmptyRatherThanNull() {
        Application application = persistedApplication();
        entityManager.flush();
        entityManager.clear();

        Application reloaded = applications.findById(application.getId()).orElseThrow();

        assertThat(reloaded.getEvaluations()).isEmpty();
    }

    /**
     * The jsonb round trip. The assessments are the reason this entity cannot be
     * tested against a generic database, and a silent serialisation failure here
     * would only surface when a recruiter opened the evaluation.
     */
    @Test
    void theAssessmentsSurviveTheJsonbRoundTrip() {
        Application application = persistedApplication();
        Evaluation saved = evaluations.save(new Evaluation(application, result(Verdict.POSSIBLE_FIT, 3)));
        entityManager.flush();
        entityManager.clear();

        Evaluation reloaded = evaluations.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getAssessments()).hasSize(2);
        assertThat(reloaded.getAssessments().getFirst().requirementId()).isEqualTo("R1");
        assertThat(reloaded.getAssessments().getFirst().status()).isEqualTo(RequirementStatus.MET);
        assertThat(reloaded.getAssessments().getFirst().requirementKind())
                .isEqualTo(RequirementKind.MUST_HAVE);
        assertThat(reloaded.getAssessments().getLast().evidenceQuote())
                .as("a null quote must survive as null, not as the string \"null\"")
                .isNull();
        assertThat(reloaded.getDimensionScores()).hasSize(2);
        assertThat(reloaded.getDimensionScores().getFirst().score()).isEqualTo(4);
    }

    private Application persistedApplication() {
        User recruiter = entityManager.persistAndFlush(
                new User("Recruiter", "hash", "recruiter" + System.nanoTime() + "@example.com",
                        Role.RECRUITER));
        User candidate = entityManager.persistAndFlush(
                new User("Candidate", "hash", "candidate" + System.nanoTime() + "@example.com",
                        Role.CANDIDATE));
        Job job = entityManager.persistAndFlush(new Job("Backend Engineer", "desc", "Senior",
                List.of(new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE)),
                recruiter));

        return entityManager.persistAndFlush(new Application(
                job, candidate, "cv.pdf", "application/pdf", 1234L, "key.pdf"));
    }

    private static EvaluationResult result(Verdict verdict, int requirementsVersion) {
        return new EvaluationResult(
                List.of(
                        new RequirementAssessment("R1", "5+ years of Java", RequirementKind.MUST_HAVE,
                                "Nine years stated.", RequirementStatus.MET, "nine years on the JVM"),
                        new RequirementAssessment("R2", "Kafka", RequirementKind.NICE_TO_HAVE,
                                "Never mentioned.", RequirementStatus.UNCLEAR, null)),
                List.of(
                        new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, "Owns outcomes.", 4, null),
                        new DimensionScore(ScoreDimension.COMMUNICATION_QUALITY, "Specific.", 5, null)),
                verdict,
                "A summary long enough to be a realistic stored value for this row.",
                "v2",
                requirementsVersion,
                3000,
                1500,
                21000L);
    }

    /**
     * {@code createdAt} is set in the constructor and has no setter, which is
     * what keeps an evaluation immutable. Two rows written microseconds apart
     * would order arbitrarily, so the ordering test needs to push them apart.
     */
    private static void setCreatedAt(Evaluation evaluation, Instant createdAt) {
        org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "createdAt", createdAt);
    }
}
