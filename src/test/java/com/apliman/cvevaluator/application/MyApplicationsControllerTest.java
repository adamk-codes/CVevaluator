package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.application.dto.ApplicationStatusResponse;
import com.apliman.cvevaluator.security.TestTokens;
import com.apliman.cvevaluator.security.WithSecurityRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read a candidate's own submission list, and whose list they get.
 */
@WebMvcTest(MyApplicationsController.class)
@WithSecurityRules
class MyApplicationsControllerTest {

    private static final long CANDIDATE_ID = 7L;
    private static final long OTHER_CANDIDATE_ID = 99L;
    private static final long RECRUITER_ID = 8L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApplicationRepository applications;

    @Test
    void mine_returnsTheCallersOwnSubmissions() throws Exception {
        when(applications.findSummariesByCandidateId(CANDIDATE_ID))
                .thenReturn(List.of(summary(5L, "cv.pdf")));

        mockMvc.perform(get("/api/me/applications").with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].originalFilename").value("cv.pdf"));
    }

    /**
     * The subject comes from the token, so there is no parameter to point at
     * someone else. This pins that the id actually reaching the query is the
     * token's — the failure this guards against is a handler that takes the
     * candidate id from anywhere the caller controls.
     */
    @Test
    void mine_readsTheSubjectFromTheTokenAndNotFromTheRequest() throws Exception {
        when(applications.findSummariesByCandidateId(anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/api/me/applications")
                        .param("candidateId", String.valueOf(OTHER_CANDIDATE_ID))
                        .with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isOk());

        verify(applications).findSummariesByCandidateId(CANDIDATE_ID);
        verify(applications, never()).findSummariesByCandidateId(OTHER_CANDIDATE_ID);
    }

    /** Nothing submitted yet is an empty list, not a 404. */
    @Test
    void mine_withNothingSubmitted_isAnEmptyList() throws Exception {
        when(applications.findSummariesByCandidateId(CANDIDATE_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/me/applications").with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void mine_recruiter_is403() throws Exception {
        mockMvc.perform(get("/api/me/applications").with(TestTokens.recruiter(RECRUITER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(applications, never()).findSummariesByCandidateId(anyLong());
    }

    @Test
    void mine_anonymous_is401() throws Exception {
        mockMvc.perform(get("/api/me/applications"))
                .andExpect(status().isUnauthorized());

        verify(applications, never()).findSummariesByCandidateId(anyLong());
    }

    private static ApplicationStatusResponse summary(Long id, String filename) {
        return new ApplicationStatusResponse(
                id, 1L, filename, ApplicationStatus.COMPLETED, null,
                1234L, 900, "PDFBOX", Instant.parse("2026-08-13T10:00:00Z"));
    }
}
