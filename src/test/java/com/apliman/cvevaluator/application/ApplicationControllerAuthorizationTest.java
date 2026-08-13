package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.application.dto.ApplicationStatusResponse;
import com.apliman.cvevaluator.job.JobRepository;
import com.apliman.cvevaluator.security.TestTokens;
import com.apliman.cvevaluator.security.WithSecurityRules;
import com.apliman.cvevaluator.storage.StorageService;
import com.apliman.cvevaluator.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may submit a CV.
 */
@WebMvcTest(ApplicationController.class)
@WithSecurityRules
class ApplicationControllerAuthorizationTest {

    private static final long CANDIDATE_ID = 7L;
    private static final long RECRUITER_ID = 8L;

    private static final MockMultipartFile CV = new MockMultipartFile(
            "file", "cv.pdf", "application/pdf", "%PDF-1.4 pretend".getBytes());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private ApplicationService applicationService;

    @MockitoBean
    private ApplicationRepository applicationRepository;

    @MockitoBean
    private JobRepository jobRepository;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void submitCv_anonymous_is401AndNeverTouchesStorage() throws Exception {
        mockMvc.perform(multipart("/api/jobs/1/applications").file(CV))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        verify(storageService, never()).store(any());
    }

    /**
     * A recruiter uploading a CV would create an application whose "candidate"
     * is the person judging it — and, since the evaluation endpoints are
     * recruiter-scoped by job ownership, one they could then read about
     * themselves. Refused at the filter chain.
     */
    @Test
    void submitCv_recruiter_is403AndNeverTouchesStorage() throws Exception {
        mockMvc.perform(multipart("/api/jobs/1/applications")
                        .file(CV)
                        .with(TestTokens.recruiter(RECRUITER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(storageService, never()).store(any());
        verify(applicationService, never()).create(anyLong(), anyLong(), any(), anyString());
    }

    /**
     * A token that verifies but names a deleted account. Rejected before the
     * file is written, so a dead token cannot litter the storage directory.
     */
    @Test
    void submitCv_tokenForADeletedUser_is401BeforeAnythingIsStored() throws Exception {
        when(jobRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(CANDIDATE_ID)).thenReturn(false);

        mockMvc.perform(multipart("/api/jobs/1/applications")
                        .file(CV)
                        .with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isUnauthorized());

        verify(storageService, never()).store(any());
    }

    /**
     * The applicant list is the whole competition on a posting — every rival's
     * filename, status and submission time.
     *
     * <p>Worth a test of its own because the failure mode is silent: this
     * endpoint arrived after the security rules were written, and with no
     * matcher naming it, it would fall through to
     * {@code anyRequest().authenticated()} and answer a candidate perfectly
     * happily. Nothing else in the suite would have noticed.
     */
    @Test
    void list_candidate_is403() throws Exception {
        mockMvc.perform(get("/api/jobs/1/applications").with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(applicationRepository, never()).findSummariesByJobId(anyLong());
    }

    /**
     * Holding the RECRUITER role is not the same as having posted the job. The
     * filter chain can only check the former, so the handler checks the latter
     * — otherwise any recruiter reads any recruiter's applicant list.
     *
     * <p>404 rather than 403: a 403 would confirm the job exists and belongs to
     * someone else, which is the answer an id-space walk is looking for.
     */
    @Test
    void list_recruiterWhoDoesNotOwnTheJob_is404AndReadsNothing() throws Exception {
        when(jobRepository.findByIdAndCreatedByRecruiter_Id(1L, RECRUITER_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/jobs/1/applications").with(TestTokens.recruiter(RECRUITER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(applicationRepository, never()).findSummariesByJobId(anyLong());
    }

    /**
     * The one endpoint both roles reach, so the query carries the authorization
     * and the test pins the caller id it is given. A candidate polling their own
     * submission must succeed; the same request for someone else's must not, and
     * both go through this one predicate.
     */
    @Test
    void status_candidate_isScopedToTheCallersOwnSubmission() throws Exception {
        when(applicationRepository.findSummaryVisibleTo(5L, 1L, CANDIDATE_ID))
                .thenReturn(Optional.of(new ApplicationStatusResponse(
                        5L, 1L, "cv.pdf", ApplicationStatus.COMPLETED, null,
                        1234L, 900, "PDFBOX", Instant.parse("2026-08-13T10:00:00Z"))));

        mockMvc.perform(get("/api/jobs/1/applications/5").with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("cv.pdf"));

        // The caller id reached the query rather than being defaulted or skipped.
        verify(applicationRepository).findSummaryVisibleTo(5L, 1L, CANDIDATE_ID);
    }

    /** Someone else's application is absent, not forbidden — see the query's javadoc. */
    @Test
    void status_applicationBelongingToAnotherUser_is404() throws Exception {
        when(applicationRepository.findSummaryVisibleTo(5L, 1L, CANDIDATE_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/jobs/1/applications/5").with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void status_anonymous_is401() throws Exception {
        mockMvc.perform(get("/api/jobs/1/applications/5"))
                .andExpect(status().isUnauthorized());

        verify(applicationRepository, never()).findSummaryVisibleTo(anyLong(), anyLong(), anyLong());
    }
}
