package com.apliman.cvevaluator.job;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The one place the rules about a requirements list live.
 *
 * <p>Both write paths — {@code POST /api/jobs} and
 * {@code PUT /api/jobs/{id}/requirements} — call
 * {@link #validateAndNormalise(List)}, so a list that is legal at creation is
 * legal at edit and vice versa. Splitting the rules between Bean Validation
 * annotations on the DTO and code here was the obvious alternative and was
 * rejected: the interesting rules are cross-field (how many are MUST_HAVE, are
 * the ids unique) and the id defaulting is a rewrite, which annotations cannot
 * express at all. Half the rules in each place is worse than all of them in one.
 *
 * <p>The method both checks and rewrites. That is deliberate — the caller gets
 * back a list it can persist as-is, and there is no window in which a validated
 * list has not yet been normalised.
 */
@Component
public class JobRequirementsValidator {

    /**
     * Twelve is a prompt-budget decision as much as an editorial one. Every
     * requirement is quoted into the evaluation prompt and answered separately
     * in the response, so the list length multiplies through the whole
     * evaluation. Twelve short lines stays comfortable; forty would not.
     */
    static final int MAX_REQUIREMENTS = 12;

    /**
     * Five is the point past which "must have" stops meaning anything. A job
     * with ten hard gates disqualifies every real candidate, and the recruiter
     * then quietly ignores the result — which is a worse outcome than making
     * them choose here.
     */
    static final int MAX_MUST_HAVES = 5;

    /**
     * Checks a submitted list and returns it with ids filled in.
     *
     * @return a list of the same size and order, every element carrying a
     *         non-blank id
     * @throws InvalidJobRequirementsException on any rule violation; the message
     *         is safe to return to the client
     */
    public List<JobRequirement> validateAndNormalise(List<JobRequirement> submitted) {
        if (submitted == null || submitted.isEmpty()) {
            throw new InvalidJobRequirementsException(
                    "A job needs at least 1 requirement.");
        }
        if (submitted.size() > MAX_REQUIREMENTS) {
            throw new InvalidJobRequirementsException(
                    "A job can have at most " + MAX_REQUIREMENTS + " requirements, but "
                            + submitted.size() + " were submitted.");
        }

        checkEachIsWellFormed(submitted);
        checkMustHaveCount(submitted);

        List<JobRequirement> normalised = withIds(submitted);
        checkIdsAreUnique(normalised);
        return normalised;
    }

    private void checkEachIsWellFormed(List<JobRequirement> submitted) {
        for (int i = 0; i < submitted.size(); i++) {
            JobRequirement requirement = submitted.get(i);
            String position = "Requirement " + (i + 1);

            if (requirement == null) {
                throw new InvalidJobRequirementsException(position + " is missing.");
            }
            if (!StringUtils.hasText(requirement.text())) {
                throw new InvalidJobRequirementsException(position + " has no text.");
            }
            if (requirement.kind() == null) {
                throw new InvalidJobRequirementsException(
                        position + " has no kind. Every requirement must be MUST_HAVE or NICE_TO_HAVE.");
            }
        }
    }

    private void checkMustHaveCount(List<JobRequirement> submitted) {
        long mustHaves = submitted.stream()
                .filter(requirement -> requirement.kind() == RequirementKind.MUST_HAVE)
                .count();

        if (mustHaves == 0) {
            throw new InvalidJobRequirementsException(
                    "A job needs at least 1 MUST_HAVE requirement.");
        }
        if (mustHaves > MAX_MUST_HAVES) {
            throw new InvalidJobRequirementsException(
                    "A job can have at most " + MAX_MUST_HAVES + " MUST_HAVE requirements, but "
                            + mustHaves + " were submitted.");
        }
    }

    /**
     * Ids are all-or-nothing: either every requirement carries one or none does.
     *
     * <p>Filling in only the blanks is the tempting alternative and it collides.
     * A client that sends {@code [{id: "R2", ...}, {id: null, ...}]} would get
     * {@code R2} generated for the second entry too, and the duplicate check
     * below would then reject a request the client had no way to see coming.
     * Refusing the mixed case outright is the rule that can be stated in one
     * sentence.
     */
    private List<JobRequirement> withIds(List<JobRequirement> submitted) {
        long supplied = submitted.stream()
                .filter(requirement -> StringUtils.hasText(requirement.id()))
                .count();

        if (supplied == submitted.size()) {
            return List.copyOf(submitted);
        }
        if (supplied > 0) {
            throw new InvalidJobRequirementsException(
                    "Requirement ids must be supplied for every requirement or for none. "
                            + supplied + " of " + submitted.size() + " were supplied.");
        }

        List<JobRequirement> normalised = new ArrayList<>(submitted.size());
        for (int i = 0; i < submitted.size(); i++) {
            JobRequirement requirement = submitted.get(i);
            normalised.add(new JobRequirement("R" + (i + 1), requirement.text(), requirement.kind()));
        }
        return List.copyOf(normalised);
    }

    private void checkIdsAreUnique(List<JobRequirement> requirements) {
        Set<String> seen = new HashSet<>();
        for (JobRequirement requirement : requirements) {
            if (!seen.add(requirement.id())) {
                throw new InvalidJobRequirementsException(
                        "Requirement ids must be unique, but '" + requirement.id() + "' appears more than once.");
            }
        }
    }
}
