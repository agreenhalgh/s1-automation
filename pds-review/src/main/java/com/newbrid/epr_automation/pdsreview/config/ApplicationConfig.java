package com.newbrid.epr_automation.pdsreview.config;

import com.newbrid.epr_automation.pdsreview.auth.Cis2NationalRbacRoles;
import com.newbrid.epr_automation.pdsreview.auth.DevNationalRbacRoles;
import com.newbrid.epr_automation.pdsreview.auth.NationalRbacRoles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.time.Clock;

/**
 * The application's wiring, and the one guard rail that cannot live anywhere else.
 */
@Configuration
@EnableConfigurationProperties(PdsReviewProperties.class)
@EnableScheduling
public class ApplicationConfig {

    /**
     * One clock, injected everywhere a timestamp is taken. Not for tidiness: the queue is ordered
     * by waiting time and locks expire on a deadline, so tests that cannot control time cannot
     * test either of them.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * CIS2 where a client is registered; the development stand-in otherwise.
     *
     * <p>And nothing at all if somebody asks for the stand-in in production. This is the check
     * that makes NFR-SEC-01 true rather than aspirational: without it, deploying with an unset
     * {@code CIS2_ISSUER_URI} would quietly fall back to three hard-coded users with access to
     * live patient records. Failing to start is the correct behaviour — an application that will
     * not boot gets fixed, and one that boots insecurely does not.
     */
    @Bean
    public NationalRbacRoles nationalRbacRoles(
            PdsReviewProperties properties,
            Environment environment,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            ObjectProvider<OAuth2AuthorizedClientService> authorizedClients) {

        boolean cis2Configured = clientRegistrations.getIfAvailable() != null;
        if (cis2Configured) {
            return new Cis2NationalRbacRoles(properties, authorizedClients.getObject());
        }

        boolean production = environment.matchesProfiles("prod");
        if (production || properties.auth().requireCis2()) {
            throw new IllegalStateException("""
                    No NHS CIS2 client is registered, and this application will not run without one.

                    Reviewers see patient-identifiable data and search PDS on their own authority, \
                    so there is no local-account mode that is safe against live data (NFR-SEC-01). \
                    Configure CIS2_ISSUER_URI and CIS2_CLIENT_ID, or — for local development only, \
                    never with a live PDS — set systmone.pds-review.auth.require-cis2=false.""");
        }
        return new DevNationalRbacRoles(properties);
    }
}
