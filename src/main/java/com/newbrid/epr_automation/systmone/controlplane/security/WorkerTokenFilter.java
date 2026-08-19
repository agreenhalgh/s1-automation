package com.newbrid.epr_automation.systmone.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates worker → control-plane calls on {@code /api/**} by a shared bearer token
 * (FR-WRK-07). A production deployment would swap this for OAuth2 client-credentials or
 * mTLS; the seam is intentionally small.
 */
public class WorkerTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Worker-Token";
    private final String expectedToken;

    public WorkerTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7);
        }
        if (expectedToken != null && expectedToken.equals(token)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "worker", null, List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
