package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.JobRepository;
import com.apliman.cvevaluator.security.TestTokens;
import com.apliman.cvevaluator.security.WithSecurityRules;
import com.apliman.cvevaluator.storage.InvalidUploadException;
import com.apliman.cvevaluator.storage.StorageService;
import com.apliman.cvevaluator.storage.StoredFile;
import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import com.apliman.cvevaluator.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApplicationController.class)
@WithSecurityRules
class ApplicationControllerTest {

    private static final long CANDIDATE_ID = 7L;

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

    private static final MockMultipartFile CV = new MockMultipartFile(
            "file", "cv.pdf", "application/pdf", "%PDF-1.4 pretend".getBytes());

    @Test
    void submitCv_writesFileToDiskBeforeInsertingTheRow() throws Exception {
        when(jobRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(CANDIDATE_ID)).thenReturn(true);

        StoredFile stored = new StoredFile("a-uuid.pdf", "cv.pdf", "pdf", 16L);
        when(storageService.store(any())).thenReturn(stored);
        when(applicationService.create(anyLong(), anyLong(), any(), anyString()))
                .thenReturn(persistedApplication());

        mockMvc.perform(multipart("/api/jobs/1/applications")
                        .file(CV)
                        .with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.jobId").value(1))
                .andExpect(jsonPath("$.originalFilename").value("cv.pdf"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // The point of this test. Both calls happening is not enough - the file
        // has to be on disk before the row that names it exists, or a reader can
        // observe a storagePath pointing at nothing. Nothing in the type system
        // enforces the order, so it is pinned here.
        InOrder inOrder = inOrder(storageService, applicationService);
        inOrder.verify(storageService).store(any());
        // The candidate id is the token subject, not anything the request body
        // or a header supplied.
        inOrder.verify(applicationService)
                .create(eq(1L), eq(CANDIDATE_ID), eq(stored), eq("application/pdf"));
    }

    @Test
    void submitCv_unknownJob_returns404WithoutTouchingStorage() throws Exception {
        when(jobRepository.existsById(999L)).thenReturn(false);

        mockMvc.perform(multipart("/api/jobs/999/applications")
                        .file(CV)
                        .with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isNotFound());

        // A rejected request must not leave a file behind. This is why the
        // existence check runs before the write rather than inside the insert.
        verify(storageService, never()).store(any());
        verify(applicationService, never()).create(anyLong(), anyLong(), any(), anyString());
    }

    /**
     * The magic-byte rejection seen from outside. FileSignatureValidatorTest
     * covers which bytes are rejected; this covers what the client gets when
     * they are - a 400 with a readable body, not a 500 and not a stack trace.
     */
    @Test
    void submitCv_contentDoesNotMatchExtension_returns400WithoutInsertingARow() throws Exception {
        when(jobRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(CANDIDATE_ID)).thenReturn(true);
        when(storageService.store(any()))
                .thenThrow(new InvalidUploadException("File content does not match its .pdf extension"));

        MockMultipartFile disguised =
                new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[] { 'M', 'Z', 0x00 });

        mockMvc.perform(multipart("/api/jobs/1/applications")
                        .file(disguised)
                        .with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("File content does not match its .pdf extension"));

        verify(applicationService, never()).create(anyLong(), anyLong(), any(), anyString());
    }

    /**
     * An Application as it looks after save() - same constructor the service
     * uses, so status and submittedAt are the real values, with the
     * database-generated id filled in by hand.
     */
    private static Application persistedApplication() {
        User candidate = new User("Candidate", "hash", "candidate@example.com", Role.CANDIDATE);
        Job job = new Job("Backend Engineer", "desc", "Senior", List.of(), null);
        Application application =
                new Application(job, candidate, "cv.pdf", "application/pdf", 16L, "a-uuid.pdf");
        ReflectionTestUtils.setField(application, "id", 42L);
        return application;
    }
}
