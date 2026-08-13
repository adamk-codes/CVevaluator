package com.apliman.cvevaluator.auth;

/**
 * A login that did not match.
 *
 * <p>One type and one message for both "no such email" and "wrong password",
 * and the message is fixed at the constructor rather than passed in, so that no
 * future caller can accidentally make the two distinguishable. Telling an
 * anonymous caller which emails exist turns a password-guessing problem into an
 * account-enumeration one.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
