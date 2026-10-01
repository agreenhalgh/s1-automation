package com.newbrid.epr_automation.pdsreview.intake;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates the gateway on the intake route.
 *
 * <p>A shared token rather than a user identity, because no user is involved: a presentation
 * arrives before anybody has looked at it. What matters is that this credential grants exactly
 * one thing — the right to hand us a presentation — and the controller behind it exposes no way
 * to read anything back (NFR-SEC-01a).
 *
 * <p>Compared in constant time. A token checked with {@code equals} leaks its prefix to anyone
 * who can measure the response, and this one is the door to a queue of patient records.
 */
public class IntakeTokenFilter extends OncePerRequestFilter {

    /** The role the gateway is granted. Nothing else in the application asks for it. */
    public static final String GATEWAY_ROLE = "ROLE_INTAKE";

    private static final String HEADER = "X-Gateway-Token";

    private final byte[] expected;
    private final String path;

    public IntakeTokenFilter(String token, String path) {
        this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        this.path = path;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (matches(presented(request))) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("gateway", null,
                            AuthorityUtils.createAuthorityList(GATEWAY_ROLE)));
        }
        chain.doFilter(request, response);
    }

    /** The header, or a bearer token — whichever the gateway is configured to send. */
    private static String presented(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7);
        }
        return null;
    }

    private boolean matches(String presented) {
        if (presented == null || expected.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
