package com.easytrading.backend.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The application's one password hasher.
 *
 * BCrypt, from spring-security-crypto. Three properties matter here:
 *
 *  - it salts every hash itself, so two users with the same password get
 *    different hashes and one cracked password does not reveal the other;
 *  - the salt is stored inside the hash string, so there is no second column to
 *    keep in step;
 *  - it is deliberately slow (2^10 rounds by default), which is what makes
 *    guessing passwords in bulk impractical. A fast hash like SHA-256 would be
 *    the wrong tool no matter how it were salted.
 *
 * Why only spring-security-crypto and not spring-boot-starter-security: the
 * starter installs a servlet filter chain that secures every endpoint by
 * default, adds CSRF protection that would reject the frontend's fetch() posts
 * until configured, and would mean maintaining Spring Security's
 * SecurityContext alongside the plain HttpSession this application uses. For
 * UC03's requirement — hash the password, verify it in the business logic layer
 * — the crypto module alone is the whole of what is needed, and one session
 * mechanism is easier to reason about than two.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
