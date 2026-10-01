package com.newbrid.epr_automation.pdsreview.config;

import com.newbrid.epr_automation.pdsreview.intake.IntakeTokenFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two perimeters, as different from each other as their users are.
 *
 * <ol>
 *   <li><b>The intake route.</b> Stateless, authenticated by a shared token, and able to do
 *       exactly one thing: accept a presentation. No user is involved because none exists yet —
 *       and no read path sits behind this credential, so it cannot become a way to read the queue
 *       without being a named clinician (NFR-SEC-01a).</li>
 *   <li><b>The reviewer UI.</b> Session-based, CIS2 where it is configured. Everything here
 *       discloses patient data, so nothing is permitted anonymously beyond health checks and the
 *       stylesheet.</li>
 * </ol>
 *
 * <p><b>Capability is not decided here.</b> Spring authorities are fixed when a session is
 * created, and this application's rule is that capability follows the national RBAC role the
 * reviewer <em>currently has selected</em>, which they can change mid-session (FR-AUTH-07a). That
 * check lives in {@code CapabilityInterceptor}, which can also explain itself — FR-AUTH-06 asks
 * for a plain account of which activity is missing, not a bare 403.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain intakeSecurity(HttpSecurity http, PdsReviewProperties properties)
            throws Exception {
        String path = properties.intake().path();
        http.securityMatcher(path + "/**", path)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests ->
                        requests.anyRequest().hasAuthority(IntakeTokenFilter.GATEWAY_ROLE))
                .addFilterBefore(new IntakeTokenFilter(properties.intake().token(), path),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain uiSecurity(HttpSecurity http,
                                          ObjectProvider<ClientRegistrationRepository> clientRegistrations)
            throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        // The NHS.UK frontend's own assets. Served from this application rather
                        // than a CDN (FR-UI-03), so they have to be reachable before sign-in or
                        // the login page renders unstyled.
                        .requestMatchers("/stylesheets/**", "/javascripts/**", "/assets/**").permitAll()
                        .anyRequest().authenticated())
                .logout(logout -> logout.logoutSuccessUrl("/").permitAll());

        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(Customizer.withDefaults());
        } else {
            // Reachable only in the development configuration; ApplicationConfig refuses to
            // start the application in this state anywhere it could see live data.
            http.formLogin(Customizer.withDefaults());
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Development sign-ins, matching the three role shapes in {@code DevNationalRbacRoles}: one
     * who can only review, one who can only audit, and one holding both activities — who must
     * still exercise them one at a time.
     *
     * <p>Only created when no CIS2 client is registered, which
     * {@link ApplicationConfig#nationalRbacRoles} already refuses to allow outside development.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            ClientRegistrationRepository.class)
    public UserDetailsService developmentUsers(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("reviewer").password(encoder.encode("reviewer")).roles("USER").build(),
                User.withUsername("auditor").password(encoder.encode("auditor")).roles("USER").build(),
                User.withUsername("both").password(encoder.encode("both")).roles("USER").build());
    }
}
