package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.application.ApplicationStatus;
import com.apliman.cvevaluator.application.CvExtractedEvent;
import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.JobRequirement;
import com.apliman.cvevaluator.job.RequirementKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
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
 * Evaluation on submission, with the model and database mocked.
 */
class SubmittedCvEvaluationListenerTest {

    private ApplicationRepository applications;
    private LlmEvaluator evaluator;
    private EvaluationService evaluationService;
    private SubmittedCvEvaluationListener listener;

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationRepository.class);
        evaluator = mock(LlmEvaluator.class);
        evaluationService = mock(EvaluationService.class);
        listener = new SubmittedCvEvaluationListener(applications, evaluator, evaluationService);
    }

    @Test
    void anExtractedCvIsEvaluatedAndStored() {
        Application application = application(ApplicationStatus.COMPLETED, "Nine years on the JVM.");
        when(applications.findById(1L)).thenReturn(Optional.of(application));
        when(evaluator.evaluate(any(), any())).thenReturn(result());

        listener.evaluate(1L);

        verify(evaluator).evaluate(any(Job.class), eq("Nine years on the JVM."));
        verify(evaluationService).record(eq(application), any(EvaluationResult.class));
    }

    /** Redacted text only. The unredacted original must never reach the model. */
    @Test
    void theModelIsGivenRedactedTextNotTheExtractedOriginal() {
        Application application = application(ApplicationStatus.COMPLETED, "Contact: [EMAIL]");
        application.setExtractedText("Contact: rami@example.com");
        when(applications.findById(1L)).thenReturn(Optional.of(application));
        when(evaluator.evaluate(any(), any())).thenReturn(result());

        listener.evaluate(1L);

        verify(evaluator).evaluate(any(Job.class), eq("Contact: [EMAIL]"));
    }

    /**
     * State is re-read rather than trusted from the event: the row can move to
     * FAILED between publish and this thread starting.
     */
    @Test
    void anApplicationThatFailedAfterThePublishIsSkipped() {
        when(applications.findById(1L))
                .thenReturn(Optional.of(application(ApplicationStatus.FAILED, null)));

        listener.evaluate(1L);

        verify(evaluator, never()).evaluate(any(), any());
    }

    @Test
    void anApplicationWithNoTextIsSkipped() {
        when(applications.findById(1L))
                .thenReturn(Optional.of(application(ApplicationStatus.COMPLETED, "   ")));

        listener.evaluate(1L);

        verify(evaluator, never()).evaluate(any(), any());
    }

    @Test
    void aDeletedApplicationIsSkippedRatherThanThrowing() {
        when(applications.findById(1L)).thenReturn(Optional.empty());

        listener.evaluate(1L);

        verify(evaluator, never()).evaluate(any(), any());
    }

    /**
     * A rejected response must not store anything, and must not escape - this
     * runs on a pool thread where an exception has nobody to catch it.
     */
    @Test
    void aRejectedResponseStoresNothingAndDoesNotEscape() {
        when(applications.findById(1L))
                .thenReturn(Optional.of(application(ApplicationStatus.COMPLETED, "text")));
        when(evaluator.evaluate(any(), any()))
                .thenThrow(new EvaluationParseException("quote not found in the CV"));

        listener.evaluate(1L);

        verify(evaluationService, never()).record(any(), any());
    }

    @Test
    void aProviderFailureDoesNotEscape() {
        when(applications.findById(1L))
                .thenReturn(Optional.of(application(ApplicationStatus.COMPLETED, "text")));
        when(evaluator.evaluate(any(), any())).thenThrow(new IllegalStateException("429"));

        listener.evaluate(1L);

        verify(evaluationService, never()).record(any(), any());
    }

    /**
     * Both annotations pinned, because both fail silently when wrong.
     *
     * <p>Without {@code AFTER_COMMIT} the listener fires inside the writing
     * transaction and reads a row whose text its connection cannot see — it
     * would evaluate an empty CV, intermittently. Without {@code @Async} the
     * evaluation runs on the extraction thread and throttles extraction to the
     * speed of the model. Neither shows up as a test failure anywhere else.
     */
    @Test
    void theListenerIsBoundAfterCommitAndOnTheEvaluationPool() throws NoSuchMethodException {
        Method handler = SubmittedCvEvaluationListener.class
                .getMethod("onCvExtracted", CvExtractedEvent.class);

        TransactionalEventListener binding = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(binding).as("must bind to the transaction, not fire inline").isNotNull();
        assertThat(binding.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);

        Async async = handler.getAnnotation(Async.class);
        assertThat(async).as("@Async is missing; this would run on the extraction thread").isNotNull();
        assertThat(async.value()).isEqualTo(EvaluationExecutorConfig.EVALUATION_EXECUTOR);
    }

    private static Application application(ApplicationStatus status, String redactedText) {
        Job job = new Job("Backend Engineer", "desc", "Senior",
                List.of(new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE)), null);
        ReflectionTestUtils.setField(job, "id", 7L);

        Application application =
                new Application(job, null, "cv.pdf", "application/pdf", 1L, "key.pdf");
        ReflectionTestUtils.setField(application, "id", 1L);
        application.setStatus(status);
        application.setRedactedText(redactedText);
        return application;
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
                "v2", 1, 3000, 1500, 21000L);
    }
}
