package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.job.RequirementKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The evaluation read endpoints as a client experiences them.
 */
@WebMvcTest(EvaluationController.class)
class EvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvaluationService evaluationService;

    @MockitoBean
    private ApplicationRepository applications;

    private Application application;

    @BeforeEach
    void applicationExists() {
        application = new Application(null, null, "cv.pdf", "application/pdf", 1L, "key.pdf");
        ReflectionTestUtils.setField(application, "id", 42L);
        when(applications.findById(42L)).thenReturn(Optional.of(application));
    }

    @Test
    void latest_returnsTheVerdictSummaryAndEveryAssessment() throws Exception {
        when(evaluationService.findLatest(any())).thenReturn(Optional.of(evaluation()));

        mockMvc.perform(get("/api/applications/42/evaluation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(42))
                .andExpect(jsonPath("$.verdict").value("POSSIBLE_FIT"))
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.assessments.length()").value(2))
                .andExpect(jsonPath("$.dimensionScores.length()").value(2))
                .andExpect(jsonPath("$.promptVersion").value("v2"))
                .andExpect(jsonPath("$.evaluatedAgainstRequirementsVersion").value(3));
    }

    /**
     * Reasoning must survive to the client on every assessment. It is the field
     * the per-requirement endpoint exists to serve, and the easiest one to lose
     * silently in a mapping.
     */
    @Test
    void latest_carriesTheReasoningAndQuoteOnEachAssessment() throws Exception {
        when(evaluationService.findLatest(any())).thenReturn(Optional.of(evaluation()));

        mockMvc.perform(get("/api/applications/42/evaluation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessments[0].requirementId").value("R1"))
                .andExpect(jsonPath("$.assessments[0].reasoning").value("Nine years stated."))
                .andExpect(jsonPath("$.assessments[0].status").value("MET"))
                .andExpect(jsonPath("$.assessments[0].evidenceQuote").value("nine years on the JVM"))
                .andExpect(jsonPath("$.assessments[1].status").value("PARTIAL"));
    }

    @Test
    void latest_returns404WhenTheApplicationHasNeverBeenEvaluated() throws Exception {
        when(evaluationService.findLatest(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/applications/42/evaluation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Application 42 has not been evaluated yet."));
    }

    @Test
    void latest_returns404ForAnUnknownApplication() throws Exception {
        when(applications.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/applications/999/evaluation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Application 999 not found"));
    }

    @Test
    void history_returnsEveryRetainedEvaluation() throws Exception {
        when(evaluationService.findHistory(any())).thenReturn(List.of(evaluation(), evaluation()));

        mockMvc.perform(get("/api/applications/42/evaluations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** A collection with nothing in it is a 200 and an empty array, not a 404. */
    @Test
    void history_returnsAnEmptyArrayWhenThereAreNoEvaluations() throws Exception {
        when(evaluationService.findHistory(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/applications/42/evaluations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** The headline case: why did R2 come out PARTIAL? */
    @Test
    void assessment_returnsTheReasoningForOneRequirement() throws Exception {
        when(evaluationService.findLatest(any())).thenReturn(Optional.of(evaluation()));

        mockMvc.perform(get("/api/applications/42/evaluation/requirements/R2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(42))
                .andExpect(jsonPath("$.evaluatedAgainstRequirementsVersion").value(3))
                .andExpect(jsonPath("$.promptVersion").value("v2"))
                .andExpect(jsonPath("$.assessment.requirementId").value("R2"))
                .andExpect(jsonPath("$.assessment.requirementText").value("Kafka"))
                .andExpect(jsonPath("$.assessment.requirementKind").value("NICE_TO_HAVE"))
                .andExpect(jsonPath("$.assessment.status").value("PARTIAL"))
                .andExpect(jsonPath("$.assessment.reasoning")
                        .value("Named in the skills list but never described in use."));
    }

    @Test
    void assessment_returns404ForARequirementTheEvaluationDoesNotCover() throws Exception {
        when(evaluationService.findLatest(any())).thenReturn(Optional.of(evaluation()));

        mockMvc.perform(get("/api/applications/42/evaluation/requirements/R9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        "The latest evaluation of application 42 has no assessment for requirement 'R9'."));
    }

    /**
     * Ids are echoed verbatim by the model and validated against the authored
     * list, so a lowercase id is not a typo to be forgiven - it is an id that
     * does not exist.
     */
    @Test
    void assessment_isCaseSensitiveOnTheRequirementId() throws Exception {
        when(evaluationService.findLatest(any())).thenReturn(Optional.of(evaluation()));

        mockMvc.perform(get("/api/applications/42/evaluation/requirements/r2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204AndAsksTheServiceToRemoveIt() throws Exception {
        mockMvc.perform(delete("/api/applications/42/evaluations/7"))
                .andExpect(status().isNoContent());

        verify(evaluationService).delete(application, 7L);
    }

    @Test
    void delete_returns404WhenTheEvaluationIsNotUnderThisApplication() throws Exception {
        doThrow(new EvaluationNotFoundException("Application 42 has no evaluation 7."))
                .when(evaluationService).delete(any(), eq(7L));

        mockMvc.perform(delete("/api/applications/42/evaluations/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Application 42 has no evaluation 7."));
    }

    @Test
    void delete_returns404ForAnUnknownApplicationWithoutTouchingTheService() throws Exception {
        when(applications.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/applications/999/evaluations/7"))
                .andExpect(status().isNotFound());

        verify(evaluationService, never()).delete(any(), any());
    }

    private Evaluation evaluation() {
        Evaluation evaluation = new Evaluation(application, new EvaluationResult(
                List.of(
                        new RequirementAssessment("R1", "5+ years of Java", RequirementKind.MUST_HAVE,
                                "Nine years stated.", RequirementStatus.MET, "nine years on the JVM"),
                        new RequirementAssessment("R2", "Kafka", RequirementKind.NICE_TO_HAVE,
                                "Named in the skills list but never described in use.",
                                RequirementStatus.PARTIAL, "Java, Spring Boot, Kafka, Kubernetes")),
                List.of(
                        new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, "Owns outcomes.", 4, null),
                        new DimensionScore(ScoreDimension.COMMUNICATION_QUALITY, "Specific.", 5, null)),
                Verdict.POSSIBLE_FIT,
                "Clears the must-haves; Kafka is listed but never described in use.",
                "v2", 3, 3922, 1683, 21980L));
        ReflectionTestUtils.setField(evaluation, "id", 7L);
        ReflectionTestUtils.setField(evaluation, "createdAt", Instant.parse("2026-08-12T10:15:30Z"));
        return evaluation;
    }
}
