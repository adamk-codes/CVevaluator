package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.JobRequirement;
import com.apliman.cvevaluator.job.RequirementKind;
import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retention cap, exercised against the real database.
 *
 * <p>Cap is pinned at 3 here rather than read from configuration. A test that
 * imports the production value passes whatever that value happens to be — it
 * would still pass if the property were quietly changed to 500, which is
 * exactly the regression worth catching. Three is also enough to prove the
 * boundary without writing five rows per case.
 */
@DataJpaTest(properties = "cvevaluator.evaluation.max-per-application=3")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(EvaluationService.class)
// @DataJpaTest is a slice: it does not run @ConfigurationPropertiesScan, so
// EvaluationProperties is not a bean here the way it is in the application.
@EnableConfigurationProperties(EvaluationProperties.class)
class EvaluationServiceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EvaluationService service;

    @Autowired
    private EvaluationRepository evaluations;

    @Test
    void recordingBelowTheCapKeepsEverything() {
        Application application = persistedApplication();

        service.record(application, result(Verdict.WEAK_FIT, 1));
        service.record(application, result(Verdict.POSSIBLE_FIT, 2));

        assertThat(evaluations.findByApplicationOrderByCreatedAtDesc(application)).hasSize(2);
    }

    @Test
    void recordingExactlyTheCapKeepsEverything() {
        Application application = persistedApplication();

        for (int version = 1; version <= 3; version++) {
            service.record(application, result(Verdict.WEAK_FIT, version));
        }

        assertThat(evaluations.findByApplicationOrderByCreatedAtDesc(application)).hasSize(3);
    }

    /**
     * The whole point. The newest survive and the oldest go, so the retained
     * window always contains the current evaluation.
     */
    @Test
    void recordingPastTheCapDropsTheOldestAndKeepsTheNewest() {
        Application application = persistedApplication();

        for (int version = 1; version <= 6; version++) {
            service.record(application, result(Verdict.WEAK_FIT, version));
        }

        assertThat(evaluations.findByApplicationOrderByCreatedAtDesc(application))
                .extracting(Evaluation::getRequirementsVersion)
                .containsExactly(6, 5, 4);
    }

    @Test
    void theLatestIsAlwaysTheMostRecentlyRecorded() {
        Application application = persistedApplication();

        for (int version = 1; version <= 6; version++) {
            service.record(application, result(Verdict.WEAK_FIT, version));
        }
        service.record(application, result(Verdict.STRONG_FIT, 7));

        Evaluation latest = service.findLatest(application).orElseThrow();
        assertThat(latest.getRequirementsVersion()).isEqualTo(7);
        assertThat(latest.getVerdict()).isEqualTo(Verdict.STRONG_FIT);
    }

    /**
     * Pruning one application's history must not touch another's. A prune
     * written against the table rather than scoped to the application would pass
     * every test above and fail this one.
     */
    @Test
    void pruningIsScopedToOneApplication() {
        Application mine = persistedApplication();
        Application theirs = persistedApplication();

        service.record(theirs, result(Verdict.STRONG_FIT, 1));
        for (int version = 1; version <= 6; version++) {
            service.record(mine, result(Verdict.WEAK_FIT, version));
        }

        assertThat(evaluations.findByApplicationOrderByCreatedAtDesc(mine)).hasSize(3);
        assertThat(evaluations.findByApplicationOrderByCreatedAtDesc(theirs)).hasSize(1);
    }

    @Test
    void findHistoryNeverExceedsTheCap() {
        Application application = persistedApplication();

        for (int version = 1; version <= 20; version++) {
            service.record(application, result(Verdict.WEAK_FIT, version));
        }

        assertThat(service.findHistory(application)).hasSize(3);
    }

    @Test
    void findLatestIsEmptyBeforeAnythingIsRecorded() {
        assertThat(service.findLatest(persistedApplication())).isEmpty();
    }

    // --- deletion --------------------------------------------------------

    @Test
    void deleteRemovesTheEvaluationAndLeavesTheRest() {
        Application application = persistedApplication();
        Evaluation first = service.record(application, result(Verdict.WEAK_FIT, 1));
        service.record(application, result(Verdict.STRONG_FIT, 2));

        service.delete(application, first.getId());

        assertThat(service.findHistory(application))
                .extracting(Evaluation::getRequirementsVersion)
                .containsExactly(2);
    }

    /**
     * Deleting the current evaluation is allowed, and the one before it becomes
     * current. That row is the one a recruiter who disagrees with a result most
     * wants gone, so refusing would block the main use.
     */
    @Test
    void deletingTheLatestPromotesThePreviousOne() {
        Application application = persistedApplication();
        service.record(application, result(Verdict.WEAK_FIT, 1));
        Evaluation latest = service.record(application, result(Verdict.STRONG_FIT, 2));

        service.delete(application, latest.getId());

        assertThat(service.findLatest(application).orElseThrow().getVerdict())
                .isEqualTo(Verdict.WEAK_FIT);
    }

    @Test
    void deletingTheOnlyEvaluationLeavesTheApplicationUnevaluated() {
        Application application = persistedApplication();
        Evaluation only = service.record(application, result(Verdict.STRONG_FIT, 1));

        service.delete(application, only.getId());

        assertThat(service.findLatest(application)).isEmpty();
    }

    /**
     * The check that matters. Without scoping, a guessed id would delete another
     * candidate's evaluation.
     */
    @Test
    void deletingAnEvaluationBelongingToAnotherApplicationIsRefused() {
        Application mine = persistedApplication();
        Application theirs = persistedApplication();
        Evaluation theirEvaluation = service.record(theirs, result(Verdict.STRONG_FIT, 1));

        assertThatThrownBy(() -> service.delete(mine, theirEvaluation.getId()))
                .isInstanceOf(EvaluationNotFoundException.class);

        assertThat(service.findHistory(theirs)).hasSize(1);
    }

    @Test
    void deletingAnUnknownEvaluationIsRefused() {
        Application application = persistedApplication();

        assertThatThrownBy(() -> service.delete(application, 999_999L))
                .isInstanceOf(EvaluationNotFoundException.class);
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
