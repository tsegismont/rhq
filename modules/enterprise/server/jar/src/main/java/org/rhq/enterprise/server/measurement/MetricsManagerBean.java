/*
 *
 *  * RHQ Management Platform
 *  * Copyright (C) 2005-2012 Red Hat, Inc.
 *  * All rights reserved.
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation version 2 of the License.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program; if not, write to the Free Software
 *  * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *
 */

package org.rhq.enterprise.server.measurement;

import java.util.List;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.domain.auth.Subject;
import org.rhq.core.domain.criteria.MeasurementDataTraitCriteria;
import org.rhq.core.domain.measurement.MeasurementReport;
import org.rhq.core.domain.measurement.TraitMeasurement;
import org.rhq.enterprise.server.measurement.instrumentation.MeasurementMonitor;
import org.rhq.enterprise.server.plugin.pc.MasterServerPluginContainer;
import org.rhq.enterprise.server.plugin.pc.metrics.MetricsServerPluginContainer;
import org.rhq.enterprise.server.plugin.pc.metrics.MetricsServerPluginFacet;
import org.rhq.enterprise.server.plugin.pc.metrics.MetricsServerPluginManager;
import org.rhq.enterprise.server.util.LookupUtil;

/**
 * @author John Sanda
 */
@Stateless
public class MetricsManagerBean implements MetricsManagerLocal {

    private Log log = LogFactory.getLog(MetricsManagerBean.class);

    @EJB
    private MeasurementScheduleManagerLocal scheduleManager;

    @Override
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<? extends TraitMeasurement> findResourceTraits(Subject subject, MeasurementDataTraitCriteria criteria) {
        MetricsServerPluginFacet metricsServer = getServerPlugin();
        return metricsServer.findTraitsByCriteria(subject, criteria);
    }

//    @Override
//    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
//    public List<ResourceTraitMeasurementDTO> findResourceTraitMeasurements(Subject subject,
//        MeasurementDataTraitCriteria criteria) {
//        MetricsServerPluginFacet metricsServer = getServerPlugin();
//        PageList<? extends TraitMeasurement> traits = metricsServer.findTraitsByCriteria(subject, criteria);
//
//        int[] scheduleIds = new int[traits.size()];
//        int i = 0;
//        for (TraitMeasurement trait : traits) {
//            scheduleIds[i++] = trait.getScheduleId();
//        }
//
//        List<MeasurementSchedule> schedules = scheduleManager.findSchedulesByIds(scheduleIds);
//
//        List<ResourceTraitMeasurementDTO> resourceTraits = new ArrayList<ResourceTraitMeasurementDTO>(traits.size());
//        for (TraitMeasurement trait : traits) {
//            MeasurementSchedule schedule = findSchedule(schedules, trait.getScheduleId());
//            resourceTraits.add(new ResourceTraitMeasurementDTO(schedule.getResource(), trait));
//        }
//
//        return resourceTraits;
//    }
//
//    private MeasurementSchedule findSchedule(List<MeasurementSchedule> schedules, int scheduleId) {
//        for (MeasurementSchedule schedule : schedules) {
//            if (schedule.getId() == scheduleId) {
//                return schedule;
//            }
//        }
//        return null;
//    }

    @Override
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void mergeMeasurementReport(MeasurementReport report) {
        MetricsServerPluginFacet metricsServer = getServerPlugin();
        long start = System.currentTimeMillis();

        metricsServer.insertMetrics(report);

        long time = System.currentTimeMillis() - start;
        MeasurementMonitor.getMBean().incrementMeasurementInsertTime(time);
        MeasurementMonitor.getMBean().incrementMeasurementsInserted(report.getDataCount());

        if (log.isDebugEnabled()) {
            log.debug("Measurement storage for [" + report.getDataCount() + "] took " + time + "ms");
        }
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void compressPurgeAndTruncate() {
        MetricsServerPluginFacet metricsServer = getServerPlugin();
        metricsServer.calculateAggregates();
    }

    private MetricsServerPluginFacet getServerPlugin() {
        MasterServerPluginContainer masterPC = LookupUtil.getServerPluginService().getMasterPluginContainer();
        if (masterPC == null) {
            log.warn(MasterServerPluginContainer.class.getSimpleName() + " is not started yet");
            throw new IllegalStateException(MasterServerPluginContainer.class.getSimpleName() + " is not started yet");
        }

        MetricsServerPluginContainer pc = masterPC.getPluginContainerByClass(MetricsServerPluginContainer.class);
        if (pc == null) {
            log.warn(MetricsServerPluginContainer.class + " has not been loaded by the " + masterPC.getClass() +
                " yet.");
            throw new IllegalStateException(MetricsServerPluginContainer.class + " has not been loaded by the " +
                masterPC.getClass() + " yet.");
        }

        MetricsServerPluginManager pluginMgr = (MetricsServerPluginManager) pc.getPluginManager();
        return pluginMgr.getMetricsServerPluginComponent();
    }
}
