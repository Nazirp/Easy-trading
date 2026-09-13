package com.easytrading.backend.user;

import com.easytrading.backend.user.dto.CredentialsRequest;
import com.easytrading.backend.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/signup, POST /api/login, POST /api/logout, GET /api/me
 * — see backend/CONTRACTS.md.
 *
 * All four are POST-with-a-JSON-body rather than GET-with-query-params, because
 * a query string is logged by the server, kept in browser history and sent in
 * the Referer header. Credentials never appear in a URL.
 *
 * Signing up logs you straight in — a separate "now log in" step after
 * registration is friction with no security benefit, since the account was just
 * created with the password the caller supplied.
 *
 * Errors are thrown as domain exceptions and mapped centrally in
 * com.easytrading.backend.common.ApiExceptionHandler:
 *   InvalidRegistrationException -> 400 INVALID_REGISTRATION
 *   InvalidCredentialsException  -> 401 INVALID_CREDENTIALS
 *   NotAuthenticatedException    -> 401 NOT_AUTHENTICATED
 *   UsernameTakenException       -> 409 USERNAME_TAKEN
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final SessionUser sessionUser;

    public AuthController(AuthService authService, SessionUser sessionUser) {
        this.authService = authService;
        this.sessionUser = sessionUser;
    }

    @PostMapping("/api/signup")
    public ResponseEntity<UserResponse> signup(@RequestBody CredentialsRequest request,
                                               HttpServletRequest httpRequest) {
        User user = authService.register(request.username(), request.password());
        sessionUser.login(httpRequest, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.of(user));
    }

    @PostMapping("/api/login")
    public UserResponse login(@RequestBody CredentialsRequest request, HttpServletRequest httpRequest) {
        User user = authService.authenticate(request.username(), request.password());
        sessionUser.login(httpRequest, user);
        return UserResponse.of(user);
    }

    /**
     * Always 204, whether or not anybody was logged in. "Log me out" has no
     * failure case worth reporting, and answering differently for a session that
     * had already expired would only confuse the frontend.
     */
    @PostMapping("/api/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        sessionUser.logout(session);
        return ResponseEntity.noContent().build();
    }

    /**
     * Who the current session belongs to — the frontend calls this on page load
     * to restore its logged-in state, rather than trusting anything it stored
     * client-side. 401 when logged out, which is the answer, not an error.
     */
    @GetMapping("/api/me")
    public UserResponse me(HttpSession session) {
        return UserResponse.of(sessionUser.require(session));
    }
}
