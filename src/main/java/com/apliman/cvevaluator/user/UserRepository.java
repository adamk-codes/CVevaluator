package com.apliman.cvevaluator.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User,Long> {

    /**
     * The login lookup.
     *
     * <p>Callers must pass an already-lowercased address. This is an exact match
     * — Postgres {@code varchar} comparison is case-sensitive, so
     * {@code Adam@x.com} would miss a row stored as {@code adam@x.com}. The
     * normalisation lives in {@code AuthService} rather than here so that the
     * same rule applies on the way in, at registration, where it is what makes
     * the unique constraint mean "one account per address" rather than "one
     * account per spelling".
     */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
