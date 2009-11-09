/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License, version 2, as
 * published by the Free Software Foundation, and/or the GNU Lesser
 * General Public License, version 2.1, also as published by the Free
 * Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License and the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License
 * and the GNU Lesser General Public License along with this program;
 * if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */

package org.rhq.core.pc.configuration;

import org.rhq.core.domain.configuration.Configuration;
import org.rhq.core.domain.resource.ResourceType;
import org.rhq.core.clientapi.agent.PluginContainerException;
import org.rhq.core.pluginapi.configuration.ResourceConfigurationFacet;
import org.rhq.core.pc.util.FacetLockType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;

public class StructuredResourceConfigurationStrategy extends BaseResourceConfigurationStrategy {

    private final Log log = LogFactory.getLog(StructuredResourceConfigurationStrategy.class);

    public Configuration loadConfiguration(int resourceId) throws PluginContainerException {
        boolean daemonOnly = true;
        boolean onlyIfStarted = true;

        ResourceConfigurationFacet facet = componentService.getComponent(resourceId,
                                                                         ResourceConfigurationFacet.class,
                                                                         FacetLockType.READ,
                                                                         FACET_METHOD_TIMEOUT,
                                                                         daemonOnly,
                                                                         onlyIfStarted);

        Configuration configuration = facet.loadStructuredConfiguration();

        if (configuration == null) {
            return null;
        }

        ResourceType resourceType = componentService.getResourceType(resourceId);

        if (configuration.getNotes() == null) {
            configuration.setNotes("Resource config for " + resourceType.getName() + " Resource w/ id " + resourceId);
        }

        configUtilityService.normalizeConfiguration(configuration, resourceType.getResourceConfigurationDefinition());
        List<String> errorMsgs = configUtilityService.validateConfiguration(configuration,
            resourceType.getResourceConfigurationDefinition());

        logErrorMsgs(errorMsgs, resourceType);

        return configuration;
    }

    private void logErrorMsgs(List<String> errorMsgs, ResourceType resourceType) {
        for (String errorMessage : errorMsgs) {
            log.warn("Plugin Error: Invalid " + resourceType.getName() + " Resource configuration returned by "
                + resourceType.getPlugin() + " plugin - " + errorMessage);
            }
    }
}
