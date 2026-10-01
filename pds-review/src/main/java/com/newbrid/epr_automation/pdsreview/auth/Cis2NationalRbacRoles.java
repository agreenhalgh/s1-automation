package com.newbrid.epr_automation.pdsreview.auth;

import com.newbrid.epr_automation.pdsreview.config.PdsReviewProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reads national RBAC roles out of a CIS2 ID token.
 *
 * <p><b>The claim name is configuration and the shape is read defensively, on purpose.</b> Open
 * Question 1 records that the exact claim carrying these roles is unconfirmed, so this class
 * accepts the two shapes the documentation implies — a list of role objects, or a single role
 * object — and tolerates the field naming differing. It logs, once, when it finds a claim it
 * cannot read, because a reviewer being told "you have no roles" when they have four is the sort
 * of failure that gets diagnosed as an outage rather than a mapping bug.
 *
 * <p>When the question is answered, this class and its tests are the only things that change.
 */
public class Cis2NationalRbacRoles implements NationalRbacRoles {

    private static final Logger log = LoggerFactory.getLogger(Cis2NationalRbacRoles.class);

    /** The field names CIS2 documentation uses, in the order they are worth trying. */
    private static final List<String> URID_FIELDS = List.of("person_roleid", "roleid", "urid");
    private static final List<String> ROLE_CODE_FIELDS = List.of("role_code", "rolecode", "role");
    private static final List<String> ROLE_NAME_FIELDS = List.of("role_name", "rolename", "display");
    private static final List<String> ORG_FIELDS = List.of("org_code", "orgcode", "person_orgid");
    private static final List<String> ORG_NAME_FIELDS = List.of("org_name", "orgname");
    private static final List<String> ACTIVITY_FIELDS = List.of("activities", "activity_codes", "activity");

    private final PdsReviewProperties properties;
    private final OAuth2AuthorizedClientService authorizedClients;

    public Cis2NationalRbacRoles(PdsReviewProperties properties,
                                 OAuth2AuthorizedClientService authorizedClients) {
        this.properties = properties;
        this.authorizedClients = authorizedClients;
    }

    @Override
    public List<NationalRbacRole> rolesFor(Authentication authentication) {
        OidcUser user = oidcUser(authentication);
        if (user == null) {
            return List.of();
        }
        Object claim = user.getClaims().get(properties.auth().claim());
        List<NationalRbacRole> roles = new ArrayList<>();
        if (claim instanceof Collection<?> entries) {
            for (Object entry : entries) {
                toRole(entry).ifPresent(roles::add);
            }
        } else if (claim != null) {
            toRole(claim).ifPresent(roles::add);
        } else {
            log.warn("CIS2 token for {} carries no '{}' claim; the reviewer will be shown no "
                            + "roles to select. Confirm the claim name (Open Question 1).",
                    userId(authentication), properties.auth().claim());
        }
        return List.copyOf(roles);
    }

    @Override
    public String userId(Authentication authentication) {
        OidcUser user = oidcUser(authentication);
        if (user == null) {
            return authentication == null ? "unknown" : authentication.getName();
        }
        // The subject, not the email: an email address can be reassigned, and an audit trail
        // attributing one person's searches to another is worse than useless.
        return user.getSubject();
    }

    @Override
    public String displayName(Authentication authentication) {
        OidcUser user = oidcUser(authentication);
        if (user == null) {
            return authentication == null ? "" : authentication.getName();
        }
        String name = user.getFullName();
        return name == null || name.isBlank() ? user.getSubject() : name;
    }

    @Override
    public String bearerToken(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            return null;
        }
        OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(
                token.getAuthorizedClientRegistrationId(), token.getName());
        return client == null || client.getAccessToken() == null
                ? null
                : client.getAccessToken().getTokenValue();
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<NationalRbacRole> toRole(Object entry) {
        if (!(entry instanceof Map<?, ?> raw)) {
            return java.util.Optional.empty();
        }
        Map<String, Object> fields = (Map<String, Object>) raw;
        String urid = first(fields, URID_FIELDS);
        if (urid == null) {
            return java.util.Optional.empty();
        }
        String roleCode = first(fields, ROLE_CODE_FIELDS);
        String roleName = first(fields, ROLE_NAME_FIELDS);
        return java.util.Optional.of(new NationalRbacRole(
                urid,
                roleCode,
                roleName == null ? (roleCode == null ? urid : roleCode) : roleName,
                first(fields, ORG_FIELDS),
                first(fields, ORG_NAME_FIELDS),
                activities(fields)));
    }

    private static String first(Map<String, Object> fields, List<String> names) {
        for (String name : names) {
            Object value = fields.get(name);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** Activities arrive as a list, or as one delimited string. Both are handled. */
    private static List<String> activities(Map<String, Object> fields) {
        for (String name : ACTIVITY_FIELDS) {
            Object value = fields.get(name);
            if (value instanceof Collection<?> entries) {
                List<String> activities = new ArrayList<>();
                entries.forEach(entry -> activities.add(String.valueOf(entry)));
                return activities;
            }
            if (value != null) {
                return List.of(String.valueOf(value).split("[,;\\s]+"));
            }
        }
        return List.of();
    }

    private static OidcUser oidcUser(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof OidcUser user
                ? user
                : null;
    }
}
