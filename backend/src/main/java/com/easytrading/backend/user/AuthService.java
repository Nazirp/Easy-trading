package com.easytrading.backend.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Business logic layer for SCRUM-39: registration and credential verification.
 *
 * Everything security-relevant lives here rather than in the controller or the
 * database:
 *
 *  - the password is hashed here, so no other layer ever sees a plaintext one;
 *  - the hash is compared here, with the encoder, never inside a SQL query;
 *  - a failed login says the same thing whether the username exists or not, so
 *    the endpoint cannot be used to enumerate accounts.
 *
 * The service knows nothing about HTTP sessions — that is SessionUser's job.
 * This class only answers "is this a valid new account?" and "are these
 * credentials correct?".
 */
@Service
public class AuthService {

    /** Matches app_user.username VARCHAR(50) in db/schema.sql. */
    static final int MAX_USERNAME_LENGTH = 50;
    static final int MIN_USERNAME_LENGTH = 3;
    static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * BCrypt only ever looks at the first 72 BYTES of a password — depending on
     * the implementation it either ignores the rest or refuses the input. Either
     * way, two passwords sharing a 72-byte prefix would be one password as far
     * as login is concerned, so anything longer is rejected here with a clear
     * message rather than quietly truncated (or blowing up as a 500).
     *
     * Measured in bytes, not characters: 72 emoji or umlauts are more than 72
     * bytes in UTF-8.
     */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new account, starting it at the default virtual balance
     * (UC04 BR1).
     *
     * @throws InvalidRegistrationException the username or password fails the
     *         rules above (400)
     * @throws UsernameTakenException       the username is already in use (409)
     */
    public User register(String rawUsername, String rawPassword) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        String password = rawPassword == null ? "" : rawPassword;

        if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) {
            throw new InvalidRegistrationException(
                    "Username must be between " + MIN_USERNAME_LENGTH + " and " + MAX_USERNAME_LENGTH
                            + " characters.");
        }
        // Two messages rather than one describing the whole rule: the short-password
        // case is the one users actually hit, and telling a beginner about a byte
        // limit they have not run into is noise. The length is in bytes below
        // because that is what BCrypt counts, but the message does not say so —
        // "too long" is all the user can act on.
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidRegistrationException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRegistrationException("That password is too long — please choose a shorter one.");
        }

        // Checked up front so the common case gets a clear error, but the
        // constraint below is what actually guarantees uniqueness: two requests
        // can both pass this check before either one inserts.
        if (userRepository.existsByUsername(username)) {
            throw new UsernameTakenException("The username '" + username + "' is already taken.");
        }

        User user = new User(username, passwordEncoder.encode(password));
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // The UNIQUE constraint on app_user.username fired -- someone else
            // registered the same name between the check above and this insert.
            // Without this catch it would surface as a 500; the frontend needs
            // the same 409 it would have got a millisecond earlier.
            //
            // saveAndFlush rather than save so the insert happens here, inside
            // the try, instead of at transaction commit after this method has
            // already returned.
            throw new UsernameTakenException("The username '" + username + "' is already taken.");
        }
    }

    /**
     * Verifies credentials and returns the matching account.
     *
     * @throws InvalidCredentialsException no such user, or wrong password (401).
     *         Deliberately the same exception and message for both: telling the
     *         two apart lets anyone discover which usernames exist.
     */
    public User authenticate(String rawUsername, String rawPassword) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        String password = rawPassword == null ? "" : rawPassword;

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new InvalidCredentialsException("Username or password is incorrect."));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Username or password is incorrect.");
        }
        return user;
    }
}
