package com.apliman.cvevaluator.auth;

/**
 * A registration naming an email that already has an account.
 *
 * <p>409 rather than 400: the request is well-formed and would have been
 * accepted a moment ago. It is the state of the world that refuses it.
 *
 * <p>This does tell an anonymous caller which emails are registered. That is a
 * real disclosure and the alternative — accepting the registration silently and
 * emailing the existing owner — needs an email pipeline this project does not
 * have. A signup form that cannot say "you already have an account" is worse for
 * every honest user, so the leak is accepted knowingly.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String email) {
        super("An account already exists for " + email);
    }
}
