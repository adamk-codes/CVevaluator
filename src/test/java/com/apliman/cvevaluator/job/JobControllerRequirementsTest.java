package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.security.HeaderCurrentUserProvider;
import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import com.apliman.cvevaluator.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The requirements rules as a client experiences them: a 400 with a readable
 * {@code ErrorResponse} body, never a stack trace and never a 500.
 *
 * <p>{@link JobRequirementsValidator} is imported rather than mocked. Mocking it
 * would leave these tests asserting that the controller calls a collaborator,
 * which is not the thing that has to be true — what has to be true is that six
 * MUST_HAVEs get rejected, and that only happens if the real rules run.
 */
@WebMvcTest(JobController.class)
@Import(JobRequirementsValidator.class)
class JobControllerRequirementsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobRepository jobRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private HeaderCurrentUserProvider currentUserProvider;

    @MockitoBean
    private ApplicationReevaluationTrigger reevaluationTrigger;

    @BeforeEach
    void recruiterExists() {
        when(currentUserProvider.currentUserId()).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(
                new User("Recruiter", "hash", "recruiter@example.com", Role.RECRUITER)));
        // save() returns its argument, so the response body reflects the entity
        // the controller actually built rather than a stub that agrees with it.
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createJob_validRequirements_persistsThemAtVersionOne() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobBody("""
                                {"id": "R1", "text": "5+ years of Java", "kind": "MUST_HAVE"},
                                {"id": "R2", "text": "Kafka", "kind": "NICE_TO_HAVE"}
                                """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requirements.length()").value(2))
                .andExpect(jsonPath("$.requirements[0].id").value("R1"))
                .andExpect(jsonPath("$.requirements[0].kind").value("MUST_HAVE"))
                .andExpect(jsonPath("$.requirementsVersion").value(1));
    }

    /**
     * The defaulting rule. Ids are what an assessment cites, so the client not
     * sending them cannot mean the assessment has nothing to point at.
     */
    @Test
    void createJob_omittedIds_areAssignedR1UpwardsInSubmissionOrder() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobBody("""
                                {"text": "5+ years of Java", "kind": "MUST_HAVE"},
                                {"text": "Spring Boot in production", "kind": "MUST_HAVE"},
                                {"text": "Kafka", "kind": "NICE_TO_HAVE"}
                                """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requirements[0].id").value("R1"))
                .andExpect(jsonPath("$.requirements[1].id").value("R2"))
                .andExpect(jsonPath("$.requirements[2].id").value("R3"))
                // Order is the submission order, not something the map reshuffled.
                .andExpect(jsonPath("$.requirements[1].text").value("Spring Boot in production"));
    }

    @Test
    void createJob_sixMustHaves_isRejected() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobBody(mustHaves(6))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("A job can have at most 5 MUST_HAVE requirements, but 6 were submitted."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(jobRepository, never()).save(any());
    }

    /** Five is the boundary and must pass, or the cap is off by one. */
    @Test
    void createJob_fiveMustHaves_isAccepted() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobBody(mustHaves(5))))
                .andExpect(status().isCreated());
    }

    @Test
    void createJob_noMustHaves_isRejected() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobBody("""
                                {"id": "R1", "text": "Kafka", "kind": "NICE_TO_HAVE"},
                                {"id": "R2", "text": "Kubernetes", "kind": "NICE_TO_HAVE"}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("A job needs at least 1 MUST_HAVE requirement."));

        verify(jobRepository, never()).save(any());
    }

    @Test
    void createJob_duplicateIds_isRejected() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobBody("""
                                {"id": "R1", "text": "5+ years of Java", "kind": "MUST_HAVE"},
                                {"id": "R1", "text": "Kafka", "kind": "NICE_TO_HAVE"}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("Requirement ids must be unique, but 'R1' appears more than once."));

        verify(jobRepository, never()).save(any());
    }

    @Test
    void createJob_noRequirementsAtAll_isRejected() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Senior Backend Engineer", "seniority": "Senior", "requirements": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A job needs at least 1 requirement."));
    }

    @Test
    void createJob_thirteenRequirements_isRejected() throws Exception {
        StringBuilder entries = new StringBuilder("""
                {"text": "5+ years of Java", "kind": "MUST_HAVE"}""");
        for (int i = 2; i <= 13; i++) {
            entries.append(",\n{\"text\": \"Nice thing ").append(i).append("\", \"kind\": \"NICE_TO_HAVE\"}");
        }

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobBody(entries.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("A job can have at most 12 requirements, but 13 were submitted."));
    }

    @Test
    void replaceRequirements_incrementsTheVersionAndCallsTheTrigger() throws Exception {
        Job job = existingJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        mockMvc.perform(put("/api/jobs/1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requirements": [
                                  {"text": "3+ years of Python in production", "kind": "MUST_HAVE"},
                                  {"text": "PyTorch", "kind": "NICE_TO_HAVE"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementsVersion").value(2))
                .andExpect(jsonPath("$.requirements.length()").value(2))
                .andExpect(jsonPath("$.requirements[0].id").value("R1"))
                .andExpect(jsonPath("$.requirements[0].text").value("3+ years of Python in production"));

        // The seam. What the trigger does with this is deliberately not asserted
        // here - today it logs, later it queues evaluations - but that it is
        // handed the job, after the version moved, is the contract.
        verify(reevaluationTrigger).onRequirementsChanged(job);
        assertThat(job.getRequirementsVersion()).isEqualTo(2);
    }

    /**
     * A rejected edit must leave the job exactly as it was. The version is the
     * thing to watch: bumping it on a request that then fails validation would
     * strand every existing evaluation against a version that never existed.
     */
    @Test
    void replaceRequirements_invalidList_leavesTheJobUntouchedAndDoesNotTrigger() throws Exception {
        Job job = existingJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        mockMvc.perform(put("/api/jobs/1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requirements": [
                                  {"id": "R1", "text": "Kafka", "kind": "NICE_TO_HAVE"}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A job needs at least 1 MUST_HAVE requirement."));

        assertThat(job.getRequirementsVersion()).isEqualTo(1);
        assertThat(job.getRequirements()).hasSize(1);
        assertThat(job.getRequirements().getFirst().text()).isEqualTo("5+ years of Java");
        verify(jobRepository, never()).save(any());
        verify(reevaluationTrigger, never()).onRequirementsChanged(any());
    }

    @Test
    void replaceRequirements_unknownJob_returns404WithoutTriggering() throws Exception {
        when(jobRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/jobs/999/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requirements": [
                                  {"text": "3+ years of Python", "kind": "MUST_HAVE"}
                                ]}
                                """))
                .andExpect(status().isNotFound());

        verify(reevaluationTrigger, never()).onRequirementsChanged(any());
    }

    private static Job existingJob() {
        Job job = new Job("Senior Backend Engineer", "desc", "Senior",
                List.of(new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE)),
                null);
        ReflectionTestUtils.setField(job, "id", 1L);
        return job;
    }

    /** Wraps requirement entries in an otherwise-valid CreateJobRequest body. */
    private static String createJobBody(String requirementEntries) {
        return """
                {
                  "title": "Senior Backend Engineer",
                  "seniority": "Senior",
                  "requirements": [%s]
                }
                """.formatted(requirementEntries);
    }

    private static String mustHaves(int count) {
        StringBuilder entries = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                entries.append(",\n");
            }
            entries.append("{\"text\": \"Required thing ").append(i).append("\", \"kind\": \"MUST_HAVE\"}");
        }
        return entries.toString();
    }
}
