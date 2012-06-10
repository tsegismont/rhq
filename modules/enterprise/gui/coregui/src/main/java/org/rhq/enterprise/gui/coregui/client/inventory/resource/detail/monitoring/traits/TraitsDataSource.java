/*
 * RHQ Management Platform
 * Copyright (C) 2010 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.gui.coregui.client.inventory.resource.detail.monitoring.traits;

import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.data.DSRequest;
import com.smartgwt.client.data.DSResponse;
import com.smartgwt.client.data.DataSourceField;
import com.smartgwt.client.data.fields.DataSourceIntegerField;
import com.smartgwt.client.rpc.RPCResponse;
import com.smartgwt.client.widgets.grid.ListGridRecord;

import org.rhq.core.domain.criteria.MeasurementDataTraitCriteria;
import org.rhq.core.domain.measurement.TraitMeasurement;
import org.rhq.enterprise.gui.coregui.client.CoreGUI;
import org.rhq.enterprise.gui.coregui.client.inventory.common.AbstractMeasurementDataTraitDataSource;

/**
 * A DataSource for reading traits for the current Resource.
 *
 * @author Ian Springer
 */
public class TraitsDataSource extends AbstractMeasurementDataTraitDataSource<TraitMeasurement> {

    private int resourceId;

    public TraitsDataSource(int resourceId) {
        this.resourceId = resourceId;
    }

    @Override
    protected List<DataSourceField> addDataSourceFields() {
        List<DataSourceField> fields = super.addDataSourceFields();

        DataSourceIntegerField resourceIdField = new DataSourceIntegerField(
            MeasurementDataTraitCriteria.FILTER_FIELD_RESOURCE_ID, MSG.common_title_resource_id());
        resourceIdField.setHidden(true);
        fields.add(0, resourceIdField);

        return fields;
    }

    @Override
    protected void executeFetch(final DSRequest request, final DSResponse response,
        final MeasurementDataTraitCriteria criteria) {
        metricsService.findResourceTraits(criteria, new AsyncCallback<List<TraitMeasurement>>() {
            @Override
            public void onFailure(Throwable caught) {
                CoreGUI.getErrorHandler().handleError(MSG.dataSource_traits_failFetch(criteria.toString()), caught);
                response.setStatus(RPCResponse.STATUS_FAILURE);
                processResponse(request.getRequestId(), response);
            }

            @Override
            public void onSuccess(List<TraitMeasurement> result) {
                dataRetrieved(result, response, request);
            }
        });
    }

    @Override
    public ListGridRecord copyValues(TraitMeasurement from) {
        ListGridRecord record = super.copyValues(from);
        record.setAttribute(MeasurementDataTraitCriteria.FILTER_FIELD_RESOURCE_ID, resourceId);
        return record;
    }

//    @Override
//    public ListGridRecord copyValues(ResourceTraitMeasurementDTO from) {
//        ListGridRecord record = super.copyValues(from);
//
//        record.setAttribute(MeasurementDataTraitCriteria.FILTER_FIELD_RESOURCE_ID, resourceId);
//
//        return record;
//    }
}
