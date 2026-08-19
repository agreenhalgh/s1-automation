package com.newbrid.epr_automation.systmone.controlplane.web.api;

import com.newbrid.epr_automation.systmone.controlplane.domain.TemplateEntity;
import com.newbrid.epr_automation.systmone.controlplane.service.RegistryService;
import com.newbrid.epr_automation.systmone.controlplane.web.dto.WebDtos.TemplateSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Read and list access to the template registry (FR-SR-08, FR-REG-01/02). */
class TemplateApiControllerTest {

    private static final byte[] PNG = {1, 2, 3, 4, 5};

    private static TemplateEntity template(String name, String s1Version) {
        return new TemplateEntity("t-" + name, name, s1Version, 0.9, "patient-record",
                "developer", "abc123", PNG);
    }

    @Test
    void listReportsMetadataAndImageSizeWithoutTheImage() {
        RegistryService registry = mock(RegistryService.class);
        when(registry.listTemplates(isNull(), any(Pageable.class)))
                .thenReturn(List.of(template("patient-button", "2024")));

        List<TemplateSummary> body = new TemplateApiController(registry).list(null, 0, 50);

        assertEquals(1, body.size());
        TemplateSummary t = body.getFirst();
        assertEquals("t-patient-button", t.id());
        assertEquals("patient-button", t.name());
        assertEquals("2024", t.s1Version());
        assertEquals(0.9, t.threshold());
        assertEquals("patient-record", t.screenContext());
        assertEquals("developer", t.createdBy());
        assertEquals("abc123", t.checksum());
        assertEquals(PNG.length, t.bytes());
    }

    @Test
    void listPassesTheVersionFilterAndPageRequestToTheRegistry() {
        RegistryService registry = mock(RegistryService.class);
        when(registry.listTemplates(eq("2024"), any(Pageable.class))).thenReturn(List.of());

        new TemplateApiController(registry).list("2024", 2, 10);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(registry).listTemplates(eq("2024"), page.capture());
        assertEquals(2, page.getValue().getPageNumber());
        assertEquals(10, page.getValue().getPageSize());
    }

    @Test
    void readReturnsOneTemplateByName() {
        RegistryService registry = mock(RegistryService.class);
        when(registry.getTemplateByName("patient-button"))
                .thenReturn(Optional.of(template("patient-button", "2024")));

        ResponseEntity<TemplateSummary> response =
                new TemplateApiController(registry).get("patient-button");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("patient-button", response.getBody().name());
    }

    @Test
    void readReturns404ForAnUnregisteredName() {
        RegistryService registry = mock(RegistryService.class);
        when(registry.getTemplateByName("missing")).thenReturn(Optional.empty());

        ResponseEntity<TemplateSummary> response = new TemplateApiController(registry).get("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }
}
