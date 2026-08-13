package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.security.TestTokens;
import com.apliman.cvevaluator.security.WithSecurityRules;
import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import com.apliman.cvevaluator.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may post and edit a job.
 */
@WebMvcTest(JobController.class)
@Import(JobRequirementsValidator.class)
@WithSecurityRules
class JobControllerAuthorizationTest {

    private static final long OWNER_ID = 7L;
    private static final long OTHER_RECRUITER_ID = 8L;
    private static final long CANDIDATE_ID = 9L;

    private static final String VALID_BODY = """
            {
              "title": "Senior Backend Engineer",
              "seniority": "Senior",
              "requirements": [{"text": "5+ years of Java", "kind": "MUST_HAVE"}]
            }
            """;

    private static final String VALID_REQUIREMENTS = """
            {"requirements": [{"text": "3+ years of Python", "kind": "MUST_HAVE"}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobRepository jobRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ApplicationReevaluationTrigger reevaluationTrigger;

    @Test
    void createJob_anonymous_is401() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        verify(jobRepository, never()).save(any());
    }

    @Test
    void createJob_candidate_is403() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .with(TestTokens.candidate(CANDIDATE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(jobRepository, never()).save(any());
    }

    /**
     * The recruiter on a new job is the token subject, not anything the client
     * can choose. Before authentication this came from an {@code X-User-Id}
     * header, which meant any caller could post a job as any recruiter.
     */
    @Test
    void createJob_attributesTheJobToTheTokenSubject() throws Exception {
        User owner = new User("Owner", "hash", "owner@example.com", Role.RECRUITER);
        ReflectionTestUtils.setField(owner, "id", OWNER_ID);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));

        mockMvc.perform(post("/api/jobs")
                        .with(TestTokens.recruiter(OWNER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());

        verify(userRepository).findById(OWNER_ID);
    }

    /**
     * A token that verifies but names a user who is gone. 401, not 500 — the
     * caller can act on "log in again".
     */
    @Test
    void createJob_tokenForADeletedUser_is401() throws Exception {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/jobs")
                        .with(TestTokens.recruiter(OWNER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verify(jobRepository, never()).save(any());
    }

    @Test
    void replaceRequirements_candidate_is403() throws Exception {
        mockMvc.perform(put("/api/jobs/1/requirements")
                        .with(TestTokens.candidate(CANDIDATE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUIREMENTS))
                .andExpect(status().isForbidden());

        verify(reevaluationTrigger, never()).onRequirementsChanged(any());
    }

    /**
     * The edit that matters. Replacing requirements re-evaluates every CV on the
     * posting, so a recruiter editing someone else's job would silently rewrite
     * another person's shortlist.
     */
    @Test
    void replaceRequirements_foreignRecruiter_is404AndDoesNotTrigger() throws Exception {
        when(jobRepository.findByIdAndCreatedByRecruiter_Id(1L, OTHER_RECRUITER_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(put("/api/jobs/1/requirements")
                        .with(TestTokens.recruiter(OTHER_RECRUITER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUIREMENTS))
                .andExpect(status().isNotFound());

        verify(jobRepository, never()).save(any());
        verify(reevaluationTrigger, never()).onRequirementsChanged(any());
    }

    @Test
    void replaceRequirements_owningRecruiter_succeeds() throws Exception {
        Job job = new Job("Senior Backend Engineer", "desc", "Senior",
                List.of(new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE)),
                null);
        ReflectionTestUtils.setField(job, "id", 1L);
        when(jobRepository.findByIdAndCreatedByRecruiter_Id(1L, OWNER_ID))
                .thenReturn(Optional.of(job));
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));

        mockMvc.perform(put("/api/jobs/1/requirements")
                        .with(TestTokens.recruiter(OWNER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUIREMENTS))
                .andExpect(status().isOk());

        verify(reevaluationTrigger).onRequirementsChanged(job);
    }

    /**
     * Browsing stays open to both roles. A candidate who cannot list jobs has
     * nothing to apply to.
     */
    @Test
    void listJobs_isOpenToCandidatesAsWellAsRecruiters() throws Exception {
        when(jobRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/jobs").with(TestTokens.candidate(CANDIDATE_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/jobs").with(TestTokens.recruiter(OWNER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void listJobs_anonymous_is401() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isUnauthorized());
    }
}
