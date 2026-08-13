-- Demo accounts. Both passwords are 'demo1234'.
--
-- The hashes are BCrypt, cost 10, matching the PasswordEncoder bean in
-- SecurityConfig. Before authentication went in this column held the literal
-- string 'herePass!!'; a plaintext value here is now simply an account that
-- cannot log in, because BCryptPasswordEncoder.matches() rejects anything that
-- is not a well-formed hash.
--
-- Emails are lower-case on purpose. AuthService folds every address with
-- Locale.ROOT before looking it up, so a seeded 'Adam.KH@gmail.com' would be
-- unreachable by any login.
--
-- IMPORTANT: this file does not run against PostgreSQL. spring.sql.init.mode
-- defaults to 'embedded', and Postgres is not embedded, so Spring Boot skips it
-- entirely. It is kept as the record of what the demo accounts are; to actually
-- apply it, either run it by hand with psql or set
-- spring.sql.init.mode=always in application.properties. ON CONFLICT makes it
-- safe to run repeatedly either way.

INSERT INTO users (name, email, password_hash, role, created_at)
VALUES ('Adam', 'adam.kh@gmail.com',
        '$2a$10$oIEmCk/kDWc.V9HJaxAE9OirVvlL8daI2bDZ9CSER5/W3mtLPAQbq',
        'RECRUITER', NOW())
ON CONFLICT (email) DO NOTHING;

INSERT INTO users (name, email, password_hash, role, created_at)
VALUES ('Demo Candidate', 'candidate@example.com',
        '$2a$10$oIEmCk/kDWc.V9HJaxAE9OirVvlL8daI2bDZ9CSER5/W3mtLPAQbq',
        'CANDIDATE', NOW())
ON CONFLICT (email) DO NOTHING;
