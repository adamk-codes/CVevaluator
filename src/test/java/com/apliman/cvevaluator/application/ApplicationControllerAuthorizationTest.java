package com.apliman.cvevaluator.application;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
}
