package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.application.ApplicationStatus;
import com.apliman.cvevaluator.evaluation.DimensionScore;
import com.apliman.cvevaluator.evaluation.EvaluationParseException;
import com.apliman.cvevaluator.evaluation.EvaluationProperties;
import com.apliman.cvevaluator.evaluation.EvaluationResult;
import com.apliman.cvevaluator.evaluation.EvaluationService;
import com.apliman.cvevaluator.evaluation.LlmEvaluator;
import com.apliman.cvevaluator.evaluation.RequirementAssessment;
import com.apliman.cvevaluator.evaluation.RequirementStatus;
import com.apliman.cvevaluator.evaluation.ScoreDimension;
import com.apliman.cvevaluator.evaluation.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The re-evaluation sweep, with the model and the database mocked out.
 *
 * <p>Timings are real but tiny — a 400ms budget polled every 20ms — so the
 * waiting logic runs for real rather than against a clock stub. The thing being
 * tested is whether a CV still being extracted is waited for, dropped, or
 * evaluated on absent text, and each of those is a different sequence of reads
 * over time.
 */
class AsyncApplicationReevaluationTriggerTest {

    private static final Duration WAIT = Duration.ofMillis(400);
    private static final Duration POLL = Duration.ofMillis(20);

    private ApplicationRepository applications;
    private EvaluationService evaluationService;
    private LlmEvaluator evaluator;
    private AsyncApplicationReevaluationTrigger trigger;

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationRepository.class);
        evaluationService = mock(EvaluationService.class);
        evaluator = mock(LlmEvaluator.class);
        trigger = new AsyncApplicationReevaluationTrigger(applications, evaluationService, evaluator,
                new EvaluationProperties(5, WAIT, POLL));

        when(evaluator.evaluate(any(), any())).thenReturn(result());
    }

    @Test
    void anAlreadyExtractedApplicationIsEvaluatedWithoutWaiting() {
        Application application = application(1L, ApplicationStatus.COMPLETED, "Nine years on the JVM.");
        eligible(application);

        long startedAt = System.nanoTime();
        trigger.onRequirementsChanged(job());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        verify(evaluator).evaluate(any(Job.class), eq("Nine years on the JVM."));
        verify(evaluationService).record(eq(application), any(EvaluationResult.class));
        assertThat(elapsedMillis)
                .as("a COMPLETED application must not be polled for at all")
                .isLessThan(WAIT.toMillis());
    }

    /**
     * The case this wait exists for: a CV uploaded moments before the
     * requirements edit, still extracting when the sweep reaches it.
     */
    @Test
    void anExtractingApplicationIsWaitedForThenEvaluated() {
        Application processing = application(1L, ApplicationStatus.PROCESSING, null);
        Application finished = application(1L, ApplicationStatus.COMPLETED, "Nine years on the JVM.");
        eligible(processing);

        // Still processing on the first re-read, done on the second.
        when(applications.findById(1L))
                .thenReturn(Optional.of(processing))
                .thenReturn(Optional.of(finished));

        trigger.onRequirementsChanged(job());

        verify(evaluator).evaluate(any(Job.class), eq("Nine years on the JVM."));
        verify(evaluationService).record(eq(finished), any(EvaluationResult.class));
    }

    /**
     * The text must come from the re-read row, not the stale one the initial
     * query returned — that instance was loaded before extraction finished and
     * its redactedText is still null.
     */
    @Test
    void theEvaluatedTextComesFromTheFreshlyReadRow() {
        Application processing = application(1L, ApplicationStatus.PROCESSING, null);
        Application finished = application(1L, ApplicationStatus.COMPLETED, "Freshly extracted text.");
        eligible(processing);
        when(applications.findById(1L)).thenReturn(Optional.of(finished));

        trigger.onRequirementsChanged(job());

        verify(evaluator).evaluate(any(Job.class), eq("Freshly extracted text."));
    }

    @Test
    void anApplicationThatNeverFinishesExtractingIsSkipped() {
        Application processing = application(1L, ApplicationStatus.PROCESSING, null);
        eligible(processing);
        when(applications.findById(1L)).thenReturn(Optional.of(processing));

        trigger.onRequirementsChanged(job());

        verify(evaluator, never()).evaluate(any(), any());
        verify(evaluationService, never()).record(any(), any());
    }

    /** The wait must be bounded, or one stuck row holds the request forever. */
    @Test
    void theWaitGivesUpAtTheConfiguredDeadline() {
        Application processing = application(1L, ApplicationStatus.PROCESSING, null);
        eligible(processing);
        when(applications.findById(1L)).thenReturn(Optional.of(processing));

        long startedAt = System.nanoTime();
        trigger.onRequirementsChanged(job());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(WAIT.toMillis());
        assertThat(elapsedMillis)
                .as("it must stop at the deadline, not run on")
                .isLessThan(WAIT.toMillis() * 4);
    }

    /**
     * Extraction can end in FAILED. The initial query filtered on the status as
     * it was then, so this state is only visible on the re-read.
     */
    @Test
    void anApplicationThatFailsExtractionWhileWaitingIsSkipped() {
        Application processing = application(1L, ApplicationStatus.PROCESSING, null);
        Application failed = application(1L, ApplicationStatus.FAILED, null);
        eligible(processing);
        when(applications.findById(1L)).thenReturn(Optional.of(failed));

        trigger.onRequirementsChanged(job());

        verify(evaluator, never()).evaluate(any(), any());
    }

    /** A COMPLETED row with no text should never reach the model. */
    @Test
    void anApplicationWithNoTextIsSkippedRatherThanEvaluatedOnNothing() {
        eligible(application(1L, ApplicationStatus.COMPLETED, "   "));

        trigger.onRequirementsChanged(job());

        verify(evaluator, never()).evaluate(any(), any());
    }

    /** One bad response must not abandon the rest of the batch. */
    @Test
    void aRejectedModelResponseDoesNotStopTheOtherApplications() {
        Application first = application(1L, ApplicationStatus.COMPLETED, "First CV.");
        Application second = application(2L, ApplicationStatus.COMPLETED, "Second CV.");
        eligible(first, second);

        when(evaluator.evaluate(any(), eq("First CV.")))
                .thenThrow(new EvaluationParseException("The model skipped 1 requirement(s): R2."));

        trigger.onRequirementsChanged(job());

        verify(evaluationService, never()).record(eq(first), any());
        verify(evaluationService).record(eq(second), any(EvaluationResult.class));
    }

    @Test
    void aProviderFailureDoesNotStopTheOtherApplications() {
        Application first = application(1L, ApplicationStatus.COMPLETED, "First CV.");
        Application second = application(2L, ApplicationStatus.COMPLETED, "Second CV.");
        eligible(first, second);

        when(evaluator.evaluate(any(), eq("First CV.")))
                .thenThrow(new IllegalStateException("429 Too Many Requests"));

        trigger.onRequirementsChanged(job());

        verify(evaluationService).record(eq(second), any(EvaluationResult.class));
    }

    /** FAILED applications are excluded by the query, so they are never loaded. */
    @Test
    void failedApplicationsAreExcludedByTheQueryItself() {
        eligible(application(1L, ApplicationStatus.COMPLETED, "First CV."));

        trigger.onRequirementsChanged(job());

        verify(applications).findByJobAndStatusNotOrderBySubmittedAt(
                any(Job.class), eq(ApplicationStatus.FAILED));
    }

    private void eligible(Application... found) {
        when(applications.findByJobAndStatusNotOrderBySubmittedAt(any(Job.class), any()))
                .thenReturn(List.of(found));
    }

    private static Application application(Long id, ApplicationStatus status, String redactedText) {
        Application application = new Application(null, null, "cv.pdf", "application/pdf", 1L, "key.pdf");
        ReflectionTestUtils.setField(application, "id", id);
        application.setStatus(status);
        application.setRedactedText(redactedText);
        return application;
    }

    private static Job job() {
        Job job = new Job("Senior Backend Engineer", "desc", "Senior",
                List.of(new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE)), null);
        ReflectionTestUtils.setField(job, "id", 7L);
        return job;
    }

    private static EvaluationResult result() {
        return new EvaluationResult(
                List.of(new RequirementAssessment("R1", "5+ years of Java", RequirementKind.MUST_HAVE,
                        "Nine years stated.", RequirementStatus.MET, "nine years on the JVM")),
                List.of(
                        new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, "Owns outcomes.", 4, null),
                        new DimensionScore(ScoreDimension.COMMUNICATION_QUALITY, "Specific.", 5, null)),
                Verdict.STRONG_FIT,
                "A summary long enough to be a realistic stored value for this row.",
                "v2", 2, 3000, 1500, 21000L);
    }
}
