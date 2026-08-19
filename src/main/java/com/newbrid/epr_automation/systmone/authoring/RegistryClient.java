package com.newbrid.epr_automation.systmone.authoring;

import com.newbrid.epr_automation.systmone.authoring.dto.AuthoringDtos.WorkerView;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;
import com.newbrid.epr_automation.systmone.controlplane.security.WorkerTokenFilter;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.LocatorSummary;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.LocatorUpsertRequest;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.TemplateSummary;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.TemplateUploadRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * The capture tool's view of the registry: it is a <b>client</b> of the control plane, not a
 * second writer to its database. Everything it saves goes through the same REST API, and the
 * same validation, a bot author would use by hand — so a template or locator authored here is
 * indistinguishable from one uploaded with curl, and the control plane keeps one audit trail.
 */
@Component
@Profile("authoring")
public class RegistryClient {

    private static final ParameterizedTypeReference<List<TemplateSummary>> TEMPLATE_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<LocatorSummary>> LOCATOR_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<WorkerView>> WORKER_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient http;

    public RegistryClient(AuthoringProperties props) {
        this.http = RestClient.builder()
                .baseUrl(props.getControlPlaneUrl())
                .defaultHeader(WorkerTokenFilter.HEADER, props.getToken())
                .build();
    }

    /** Workers the control plane knows about, i.e. the machines a capture can be requested from. */
    public List<WorkerView> workers() {
        return orEmpty(http.get().uri("/api/workers").retrieve().body(WORKER_LIST));
    }

    public List<TemplateSummary> templates() {
        return orEmpty(http.get().uri("/api/templates?size=500").retrieve().body(TEMPLATE_LIST));
    }

    public byte[] templateImage(String name) {
        return http.get().uri("/api/templates/{name}/image", name).retrieve().body(byte[].class);
    }

    /** @return the registry id of the stored template */
    public String uploadTemplate(String name, String s1Version, double threshold, String screenContext,
                                 String dataBase64) {
        Map<String, String> response = http.post().uri("/api/templates")
                .body(new TemplateUploadRequest(name, s1Version, threshold, screenContext, dataBase64))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return response == null ? null : response.get("templateId");
    }

    public List<LocatorSummary> locators() {
        return orEmpty(http.get().uri("/api/locators").retrieve().body(LOCATOR_LIST));
    }

    /** @return the registry id of the stored locator */
    public String saveLocator(String name, String s1Version, LocatorDefinition definition) {
        Map<String, String> response = http.post().uri("/api/locators")
                .body(new LocatorUpsertRequest(name, s1Version, definition))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return response == null ? null : response.get("locatorId");
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
