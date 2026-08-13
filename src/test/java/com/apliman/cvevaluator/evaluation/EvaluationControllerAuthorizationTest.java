package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.security.TestTokens;
import com.apliman.cvevaluator.security.WithSecurityRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read an evaluation.
 *
 * <p>The most consequential rules in the application: an evaluation contains a
 * model's judgement of a named person, quoted from their CV. The tests below
 * are the ones to break loudly if the policy ever drifts.
 */
@WebMvcTest(EvaluationController.class)
@WithSecurityRules
class EvaluationControllerAuthorizationTest {

    private static final long OWNER_ID = 7L;
    private static final long OTHER_RECRUITER_ID = 8L;
    private static final long CANDIDATE_ID = 9L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvaluationService evaluationService;

    @MockitoBean
    private ApplicationRepository applications;

    @Test
    void anonymous_gets401AndAnErrorResponseBody() throws Exception {
        mockMvc.perform(get("/api/applications/42/evaluation"))
                .andExpect(status().isUnauthorized())
                // The point of the ErrorResponseWriter. Without it this is
                // Tomcat's HTML error page, which breaks the contract that
                // every failure in this API is a JSON ErrorResponse.
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verifyNoInteractions(applications, evaluationService);
    }

    /**
     * The rule the product asked for: a candidate never sees the assessment of
     * their own CV.
     */
    @Test
    void candidate_isRefusedEvenForTheirOwnApplication() throws Exception {
        mockMvc.perform(get("/api/applications/42/evaluation")
                        .with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // Refused in the filter chain, so nothing was even looked up. That is
        // what makes the 403 identical whether or not application 42 exists.
        verifyNoInteractions(applications, evaluationService);
    }

    @Test
    void candidate_isRefusedTheHistoryAndThePerRequirementViewToo() throws Exception {
        mockMvc.perform(get("/api/applications/42/evaluations")
                        .with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/applications/42/evaluation/requirements/R1")
                        .with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(applications, evaluationService);
    }

    /**
     * A recruiter who did not post the job gets 404, not 403.
     *
     * <p>404 is the whole point: a 403 would confirm the application exists,
     * which is exactly what a recruiter walking the id space wants to learn.
     */
    @Test
    void foreignRecruiter_gets404IndistinguishableFromAnUnknownId() throws Exception {
        Application owned = application();
        when(applications.findByIdAndJob_CreatedByRecruiter_Id(42L, OWNER_ID))
                .thenReturn(Optional.of(owned));
        when(applications.findByIdAndJob_CreatedByRecruiter_Id(42L, OTHER_RECRUITER_ID))
                .thenReturn(Optional.empty());

        // Same id, two recruiters, two different answers.
        when(evaluationService.findLatest(owned)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/applications/42/evaluation")
                        .with(TestTokens.recruiter(OTHER_RECRUITER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Application 42 not found"));

        // And an id that genuinely does not exist gives byte-identical output.
        mockMvc.perform(get("/api/applications/42/evaluation")
                        .with(TestTokens.recruiter(999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Application 42 not found"));
    }

    /**
     * Delete is the one that would do damage, so it gets its own case rather
     * than being assumed to follow from the reads.
     */
    @Test
    void foreignRecruiter_cannotDeleteAnotherRecruitersEvaluation() throws Exception {
        when(applications.findByIdAndJob_CreatedByRecruiter_Id(42L, OTHER_RECRUITER_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/applications/42/evaluations/7")
                        .with(TestTokens.recruiter(OTHER_RECRUITER_ID)))
                .andExpect(status().isNotFound());

        verify(evaluationService, never()).delete(any(), any());
    }

    @Test
    void owningRecruiter_isAllowedThrough() throws Exception {
        Application owned = application();
        when(applications.findByIdAndJob_CreatedByRecruiter_Id(42L, OWNER_ID))
                .thenReturn(Optional.of(owned));

        mockMvc.perform(delete("/api/applications/42/evaluations/7")
                        .with(TestTokens.recruiter(OWNER_ID)))
                .andExpect(status().isNoContent());

        verify(evaluationService).delete(owned, 7L);
    }

    private static Application application() {
        Application application =
                new Application(null, null, "cv.pdf", "application/pdf", 1L, "key.pdf");
        ReflectionTestUtils.setField(application, "id", 42L);
        return application;
    }
}
