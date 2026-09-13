package com.easytrading.backend.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The one place that knows how "who is logged in" is stored.
 *
 * The session holds only the user's id; the row is re-read on each request, so a
 * balance changed by a trade is never served from a stale copy sitting in the
 * session. The id travels in the standard servlet session, which means the
 * browser only ever holds the JSESSIONID cookie — no user data, nothing worth
 * tampering with.
 *
 * Every user-scoped endpoint that follows (watchlist, trades, journal) should
 * take an HttpSession and call {@link #require} rather than reading the session
 * attribute itself: one definition of "logged in", one 401.
 */
@Component
public class SessionUser {

    private static final String USER_ID = "userId";

    private final UserRepository userRepository;

    public SessionUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Marks this browser as logged in.
     *
     * If a session already exists, its id is rotated first. Without that, an id
     * an attacker had planted in the victim's browser before login would still
     * be valid afterwards — and would now name an authenticated session
     * (session fixation). Where there is no session yet, the one created below
     * is new by definition, and changeSessionId() would throw.
     */
    public void login(HttpServletRequest request, User user) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        request.getSession().setAttribute(USER_ID, user.getId());
    }

    /** Ends the session. Safe to call when nobody is logged in. */
    public void logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
    }

    /** The logged-in user, or empty if this browser has no session. */
    public Optional<User> current(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }
        Object userId = session.getAttribute(USER_ID);
        if (userId == null) {
            return Optional.empty();
        }
        // Empty rather than a 500 if the row is gone (account deleted while the
        // cookie lived on) -- to the caller that is simply "not logged in".
        return userRepository.findById((Long) userId);
    }

    /**
     * The logged-in user, or a 401.
     *
     * @throws NotAuthenticatedException nobody is logged in on this session
     */
    public User require(HttpSession session) {
        return current(session)
                .orElseThrow(() -> new NotAuthenticatedException("You need to be logged in to do that."));
    }
}
