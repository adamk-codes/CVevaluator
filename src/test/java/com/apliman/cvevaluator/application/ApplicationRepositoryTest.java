package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.JobRequirement;
import com.apliman.cvevaluator.job.RequirementKind;
import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApplicationRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Test
    void findByJobOrderBySubmittedAt_returnsAllApplicationsForJob() {
        User recruiter = entityManager.persistAndFlush(
                new User("Recruiter", "hash", "recruiter@example.com", Role.RECRUITER));
        Job job = entityManager.persistAndFlush(
                new Job("Backend Engineer", "desc", "Senior",
                        List.of(new JobRequirement("R1", "3+ years Java", RequirementKind.MUST_HAVE)),
                        recruiter));

        for (int i = 1; i <= 3; i++) {
            User candidate = entityManager.persistAndFlush(
                    new User("Candidate " + i, "hash", "candidate" + i + "@example.com", Role.CANDIDATE));
            entityManager.persistAndFlush(
                    new Application(job, candidate, "cv" + i + ".pdf", "application/pdf",
                            1024L, "storage/cv" + i + ".pdf"));
        }

        // Empty the persistence context so the fetch below is a real round trip
        // to the database, not answered from the first-level cache.
        entityManager.clear();

        List<Application> applications = applicationRepository.findByJobOrderBySubmittedAt(job);

        assertThat(applications).hasSize(3);

        // Each getCandidate().getEmail() below fires its own SELECT against `users` -
        // that's the N+1. Watch the SQL log (show-sql=true) while this runs.
        for (Application application : applications) {
            assertThat(application.getCandidate().getEmail()).isNotBlank();
        }
    }
}
