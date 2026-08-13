package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.security.TestTokens;
import com.apliman.cvevaluator.security.WithSecurityRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A recruiter's dashboard lists their own postings and nobody else's.
 */
@WebMvcTest(MyJobsController.class)
@WithSecurityRules
class MyJobsControllerTest {

    private static final long RECRUITER_ID = 8L;
    private static final long OTHER_RECRUITER_ID = 99L;
    private static final long CANDIDATE_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobRepository jobs;

    /**
     * Regression: the dashboard used to call {@code GET /api/jobs} and list
     * every posting on the platform, while the applicant list under each row
     * was scoped to whoever posted the job. Opening someone else's row rendered
     * the job and then answered "Job not found" beneath it.
     *
     * <p>This asserts the id reaching the query is the token's, which is what
     * makes the two views agree about which jobs exist for this recruiter.
     */
    @Test
    void mine_listsOnlyTheCallersOwnPostings() throws Exception {
        when(jobs.findByCreatedByRecruiter_IdOrderByCreatedAtDesc(RECRUITER_ID))
                .thenReturn(List.of(job(1L, "Mine")));

        mockMvc.perform(get("/api/me/jobs").with(TestTokens.recruiter(RECRUITER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Mine"));

        verify(jobs).findByCreatedByRecruiter_IdOrderByCreatedAtDesc(RECRUITER_ID);
        verify(jobs, never()).findByCreatedByRecruiter_IdOrderByCreatedAtDesc(OTHER_RECRUITER_ID);
        // The bug was reading every job. Nothing here may call findAll.
        verify(jobs, never()).findAll();
    }

    @Test
    void mine_withNoPostings_isAnEmptyList() throws Exception {
        when(jobs.findByCreatedByRecruiter_IdOrderByCreatedAtDesc(RECRUITER_ID))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/me/jobs").with(TestTokens.recruiter(RECRUITER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void mine_candidate_is403() throws Exception {
        mockMvc.perform(get("/api/me/jobs").with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isForbidden());

        verify(jobs, never()).findByCreatedByRecruiter_IdOrderByCreatedAtDesc(anyLong());
    }

    @Test
    void mine_anonymous_is401() throws Exception {
        mockMvc.perform(get("/api/me/jobs"))
                .andExpect(status().isUnauthorized());

        verify(jobs, never()).findByCreatedByRecruiter_IdOrderByCreatedAtDesc(anyLong());
    }

    private static Job job(Long id, String title) {
        Job job = new Job(title, "desc", "Mid",
                List.of(new JobRequirement("R1", "3+ years of Java", RequirementKind.MUST_HAVE)),
                null);
        ReflectionTestUtils.setField(job, "id", id);
        ReflectionTestUtils.setField(job, "createdAt", java.time.Instant.parse("2026-08-13T10:00:00Z"));
        return job;
    }
}
