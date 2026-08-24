package com.newbrid.epr_automation.systmone.config;

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
 * A third perimeter, alongside the machine surface and the operator UI: the Prometheus
 * scrape endpoint (NFR-OBS-03). It exists on every role — control plane, capture tool and
 * worker — because the collector has to reach all three, so this configuration is not
 * profile-scoped and deliberately overlaps none of the others.
 *
 * <p>Scraping is authenticated. A metrics endpoint is usually treated as harmless, but here
 * it names bots and reports run counts and failure rates against them, and the endpoint is
 * open to the network in a way the session-authenticated UI is not. HTTP Basic over a
 * dedicated {@code ROLE_METRICS} account is what a Prometheus scrape config can present
 * without holding a session, and it keeps the collector's credential separate from both the
 * operator logins and the worker token — none of the three can be used in place of another.
 *
 * <p>The chain carries its own {@link ProviderManager} rather than authenticating against
 * whatever {@code UserDetailsService} the active role happens to define: the control plane's
 * is development users only ({@code @Profile("!prod")}) and a worker has none at all, so
 * borrowing the ambient one would make scraping work or fail depending on the role and the
 * environment.
 */
@Configuration
@EnableWebSecurity
public class MetricsSecurityConfig {

    /** Where Boot maps the Prometheus registry; also what docker/prometheus/prometheus.yml asks for. */
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
                // A scrape is a GET every 15s from a process that holds no session and no
                // CSRF token, so both are off — as they are on the /api/** machine surface.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(new ProviderManager(
                        new DaoAuthenticationProvider(new InMemoryUserDetailsManager(scraper))))
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("METRICS"))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
