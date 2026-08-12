package com.apliman.cvevaluator.evaluation;

/**
 * The evaluation, or the part of one, that was asked for does not exist.
 *
 * <p>Covers three different 404s that a client cannot usefully tell apart:
 * the application has never been evaluated, the evaluation id is unknown, and
 * the requirement id is not in the evaluation that was found. Each carries its
 * own message.
 *
 * <p>"Never evaluated" being a 404 rather than an empty 200 is the debatable
 * one, and it is deliberate: an application sits at COMPLETED with no
 * evaluation for a normal and possibly long stretch, and a client that gets a
 * 200 with nulls in it has to check every field before using any of them. A 404
 * says "not yet" in one place.
 */
public class EvaluationNotFoundException extends RuntimeException {

    public EvaluationNotFoundException(String message) {
        super(message);
    }

    static EvaluationNotFoundException forApplication(Long applicationId) {
        return new EvaluationNotFoundException(
                "Application " + applicationId + " has not been evaluated yet.");
    }

    static EvaluationNotFoundException forRequirement(Long applicationId, String requirementId) {
        return new EvaluationNotFoundException(
                "The latest evaluation of application " + applicationId
                        + " has no assessment for requirement '" + requirementId + "'.");
    }

    /**
     * Used both when the id is unknown and when it belongs to a different
     * application. The same message for both on purpose: a caller who guesses
     * ids should not be able to learn which ones exist from the difference
     * between two error messages.
     */
    static EvaluationNotFoundException forId(Long applicationId, Long evaluationId) {
        return new EvaluationNotFoundException(
                "Application " + applicationId + " has no evaluation " + evaluationId + ".");
    }
}
