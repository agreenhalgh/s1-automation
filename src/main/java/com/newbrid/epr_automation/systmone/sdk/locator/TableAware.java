package com.newbrid.epr_automation.systmone.sdk.locator;

import com.newbrid.epr_automation.systmone.automation.jab.TableGeometry;
import com.newbrid.epr_automation.systmone.common.dto.LocatorDefinition;

import java.time.Duration;

/**
 * A backend that can ask a table how big it is, rather than counting what the tree exposes.
 *
 * <p>A separate interface because only one backend can: a template match knows nothing about rows,
 * and an image locator asked for a row count could only guess. Keeping it off
 * {@link AutomationBackend} means a backend that cannot answer does not have to pretend to.</p>
 */
public interface TableAware {

    /**
     * The size of the table this locator resolves to, or null when it resolves to something else,
     * to nothing, or to a table the application will not describe.
     */
    TableGeometry tableGeometry(LocatorDefinition locator, Duration timeout);
}
