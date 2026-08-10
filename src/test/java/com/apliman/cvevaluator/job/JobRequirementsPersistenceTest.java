package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The requirements list survives a round trip through the {@code jsonb} column.
 *
 * <p>Runs against the real PostgreSQL ({@code Replace.NONE}) and could not
 * usefully do otherwise — {@code jsonb} is the thing under test, and an
 * in-memory database would either reject the type or quietly store a string,
 * which is the exact failure this exists to catch.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobRequirementsPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JobRepository jobRepository;

    private static final List<JobRequirement> REQUIREMENTS = List.of(
            new JobRequirement("R1", "5+ years writing production backend services in Java",
                    RequirementKind.MUST_HAVE),
            new JobRequirement("R2", "Relational data modelling and SQL on PostgreSQL",
                    RequirementKind.MUST_HAVE),
            new JobRequirement("R3", "Kafka or another log-based message broker",
                    RequirementKind.NICE_TO_HAVE));

    @Test
    void requirements_roundTripThroughJsonb() {
        Long id = persistJob(REQUIREMENTS).getId();

        // Without this the assertions below read the same instance back out of
        // the first-level cache and prove nothing about what reached the column.
        entityManager.clear();

        Job reloaded = jobRepository.findById(id).orElseThrow();

        // Order, ids and kinds all matter: an assessment cites "R2", so a list
        // that comes back reordered or with a kind flattened to a string would
        // silently change what the evaluation was answering.
        assertThat(reloaded.getRequirements()).containsExactlyElementsOf(REQUIREMENTS);
        assertThat(reloaded.getRequirementsVersion()).isEqualTo(1);
        assertThat(reloaded.getRequirementsText()).isEqualTo("Required: 5+ years of Java. Nice to have: Kafka.");
    }

    /**
     * Pins the storage type, not just the behaviour. Hibernate would happily map
     * this list to {@code text} and every other assertion here would still pass;
     * the difference only shows up later, the first time a query wants to filter
     * on a requirement.
     */
    @Test
    void requirementsColumn_isJsonb() {
        Object dataType = entityManager.getEntityManager()
                .createNativeQuery("""
                        select data_type from information_schema.columns
                        where table_name = 'jobs' and column_name = 'requirements_json'
                        """)
                .getSingleResult();

        assertThat(dataType).isEqualTo("jsonb");
    }

    @Test
    void replaceRequirements_persistsTheNewListAndTheBumpedVersion() {
        Job job = persistJob(REQUIREMENTS);
        Long id = job.getId();

        List<JobRequirement> replacement = List.of(
                new JobRequirement("R1", "3+ years of Python in production", RequirementKind.MUST_HAVE));
        job.replaceRequirements(replacement);
        entityManager.flush();
        entityManager.clear();

        Job reloaded = jobRepository.findById(id).orElseThrow();

        assertThat(reloaded.getRequirements()).containsExactlyElementsOf(replacement);
        assertThat(reloaded.getRequirementsVersion()).isEqualTo(2);
    }

    /**
     * The getter hands back something a caller cannot edit in place. If it
     * handed out the live list, an {@code add} on it would be dirty-checked and
     * persisted with the version left where it was — which is the one way an
     * evaluation's recorded version could stop matching the requirements it was
     * actually made against.
     */
    @Test
    void getRequirements_cannotBeEditedInPlace() {
        Long id = persistJob(REQUIREMENTS).getId();
        entityManager.clear();

        List<JobRequirement> handedOut = jobRepository.findById(id).orElseThrow().getRequirements();

        assertThatThrownBy(() -> handedOut.add(
                new JobRequirement("R4", "Snuck in", RequirementKind.MUST_HAVE)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private Job persistJob(List<JobRequirement> requirements) {
        User recruiter = entityManager.persistAndFlush(
                new User("Recruiter", "hash", "recruiter@example.com", Role.RECRUITER));
        return entityManager.persistAndFlush(new Job(
                "Senior Backend Engineer",
                "We are building the transaction core of a payments platform.",
                "Required: 5+ years of Java. Nice to have: Kafka.",
                "Senior",
                requirements,
                recruiter));
    }
}
