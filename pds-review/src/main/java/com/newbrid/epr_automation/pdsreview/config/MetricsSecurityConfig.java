package com.newbrid.epr_automation.pdsreview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A third perimeter, alongside the intake route and the reviewer UI: the Prometheus scrape
 * endpoint (NFR-OBS-02, NFR-OBS-03). The same shape as the control plane's, deliberately, so the
 * collector presents one kind of credential across the estate.
 *
 * <p>Scraping is authenticated, and here the reason is sharper than usual. These metrics count
 * patients waiting for identification, broken down by triage score, and decisions broken down by
 * reason — including how often reviewers close items without registering anybody. None of that
 * names a patient, but queue depth by urgency in an ED is operationally sensitive and the
 * endpoint is open to the network in a way the session-authenticated UI is not.
 *
 * <p>HTTP Basic over a dedicated {@code ROLE_METRICS} account is what a Prometheus scrape config
 * can present without holding a session. The chain carries its own authentication manager rather
 * than borrowing the ambient one, which in this application is either CIS2 — where there is no
 * password to present — or the development users, which do not exist in production.
 */
@Configuration
@EnableWebSecurity
public class MetricsSecurityConfig {

    /** Where Boot maps the Prometheus registry, and what the collector asks for. */
    public static final String SCRAPE_PATH = "/actuator/prometheus";

    @Bean
    @Order(0)
    public SecurityFilterChain metricsSecurity(
            HttpSecurity http,
            @Value("${systmone.metrics.username:prometheus}") String username,
            @Value("${systmone.metrics.password:prometheus}") String password) throws Exception {

        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var scraper = User.withUsername(username)
                .password(encoder.encode(password))
                .roles("METRICS")
                .build();

        http.securityMatcher(SCRAPE_PATH)
                // A scrape is a GET every fifteen seconds from a process holding no session and
                // no CSRF token, so both are off — as on the intake route.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(new ProviderManager(
                        new DaoAuthenticationProvider(new InMemoryUserDetailsManager(scraper))))
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("METRICS"))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
