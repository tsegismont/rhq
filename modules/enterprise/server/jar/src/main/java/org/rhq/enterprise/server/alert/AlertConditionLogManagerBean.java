/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
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
package org.rhq.enterprise.server.alert;

import java.util.List;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.exception.ConstraintViolationException;

import org.jboss.annotation.ejb.PoolClass;
import org.jboss.ejb3.StrictMaxPool;
import org.rhq.core.domain.alert.AlertCondition;
import org.rhq.core.domain.alert.AlertConditionLog;
import org.rhq.core.domain.alert.AlertDampeningEvent;
import org.rhq.core.domain.alert.AlertDefinition;
import org.rhq.core.domain.alert.BooleanExpression;
import org.rhq.enterprise.server.RHQConstants;

/**
 * @author Joseph Marques
 */
@Stateless
// NOTE: The AlertConditionLogManagerBean, AlertConditionManagerBean, AlertDampeningManagerBean,
//       AlertDefinitionManagerBean, and CachedConditionManagerBean SLSB's are all invoked, either directly or
//       indirectly, by the AlertConditionConsumerBean MDB. Since MDB invocations are always done in new threads, using
//       the default SLSB pool impl ({@link ThreadlocalPool}) would cause a new instance of this SLSB to be created
//       every time it was invoked by AlertConditionConsumerBean. This would be bad because an existing instance would
//       not be reused, but it is really bad because the instance would also never get destroyed, causing heap space to
//       gradually leak until the Server eventually ran out of memory. Hence, we must use a {@link StrictMaxPool}, which
//       will use a fixed pool of instances of this SLSB, instead of a ThreadlocalPool. Because most of these SLSB's are
//       also invoked by other callers (i.e. Agents, GUI's, or CLI's) besides AlertConditionConsumerBean, we set the max
//       pool size to 100 (30 is the default value), to lower the chances of AlertConditionConsumerBean
//       invocations, which are the most critical, from having to block and potentially getting backed up in the queue.
//       For more details, see https://bugzilla.redhat.com/show_bug.cgi?id=693232 (ips, 05/05/11).
@PoolClass(value = StrictMaxPool.class, maxSize = 100)
public class AlertConditionLogManagerBean implements AlertConditionLogManagerLocal {
    private final Log log = LogFactory.getLog(AlertConditionLogManagerBean.class);

    @EJB
    private AlertConditionLogManagerLocal alertConditionLogManager;
    @EJB
    private AlertConditionManagerLocal alertConditionManager;
    @EJB
    private AlertDampeningManagerLocal alertDampeningManager;

    @PersistenceContext(unitName = RHQConstants.PERSISTENCE_UNIT_NAME)
    private EntityManager entityManager;

    public AlertConditionLog getUnmatchedLogByAlertConditionId(int alertConditionId) {
        Query query = entityManager.createNamedQuery(AlertConditionLog.QUERY_FIND_UNMATCHED_LOG_BY_ALERT_CONDITION_ID);
        query.setParameter("alertConditionId", alertConditionId);

        return (AlertConditionLog) query.getSingleResult();
    }

    @SuppressWarnings("unchecked")
    public List<AlertConditionLog> getUnmatchedLogsByAlertDefinitionId(int alertDefinitionId) {
        Query unmatchedLogsQuery = entityManager
            .createNamedQuery(AlertConditionLog.QUERY_FIND_UNMATCHED_LOGS_BY_ALERT_DEFINITION_ID);
        unmatchedLogsQuery.setParameter("alertDefinitionId", alertDefinitionId);

        List<AlertConditionLog> unmatchedConditionLogs = unmatchedLogsQuery.getResultList();

        return unmatchedConditionLogs;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void updateUnmatchedLogByAlertConditionId(int alertConditionId, long ctime, String value) {
        /*
         * this method is marked as REQUIRES_NEW because I want this log work to complete before I resume the
         * outer-scoping transaction, which will operate on the results.
         */
        try {
            try {
                AlertConditionLog alertConditionLog = this.getUnmatchedLogByAlertConditionId(alertConditionId);

                /*
                 * No exceptions.
                 *
                 * This means that there was exactly one existing, unmatched, active alert condition.  This is another
                 * positive event associated against the same alertCondition, so we're going to use its data to update the
                 * "ctime" and "value" properties.
                 */
                alertConditionLog.setCtime(ctime);
                alertConditionLog.setValue(value);
                log.debug("Updating unmatched alert condition log: " + alertConditionLog);

                entityManager.merge(alertConditionLog); // update values, for
                entityManager.flush();
            } catch (NoResultException nre) { // this is the expected case 90% of the time
                // lookup the condition entity
                AlertCondition condition = entityManager.find(AlertCondition.class, alertConditionId);
                if (condition == null) {
                    // the associated AlertDefinition must have been updated, which removes all previous AlertConditions
                }

                // persist the log entry
                AlertConditionLog conditionLog = new AlertConditionLog(condition, ctime);
                conditionLog.setValue(value);

                log.debug("Inserting unmatched alert condition log: " + conditionLog);

                entityManager.persist(conditionLog);
                entityManager.flush();
            } catch (NonUniqueResultException nure) {
                // serious bug in the processing logic
                log.debug("Found multiple unmatched results for alertConditionId of " + alertConditionId
                    + " while performing activation.  There should only be one.");
            }
        } catch (Throwable t) {
            Throwable throwable = t;
            boolean found = false;
            while (throwable != null) {
                if (throwable instanceof ConstraintViolationException) {
                    // we're trying to persist a log entry for an AlertCondition that was just deleted
                    log.debug("ConstraintViolationException thrown during AlertConditionLog persistence");
                    found = true;
                    break;
                }
                throwable = throwable.getCause();
            }
            if (!found) {
                throw new RuntimeException(
                    "Could not insert log entry for AlertCondition[id=" + alertConditionId + "]", t);
            }
        }
    }

    public void removeUnmatchedLogByAlertConditionId(int alertConditionId) {
        try {
            AlertConditionLog alertConditionLog = this.getUnmatchedLogByAlertConditionId(alertConditionId);

            log.debug("Removing unmatched alert condition log: " + alertConditionLog);

            entityManager.remove(alertConditionLog);
        } catch (NoResultException nre) {
            /*
             * This is OK.
             *
             * At the time the cache fired it thought that there was an unmatched, active condition that included this
             * condition.  However, in the time between the sending of the JMS message to "now", the out-of-band process
             * came along and matched all necessary conditions and created an alert.  The act of creating an alert and
             * associating this alertConditionId with it means that it can no longer be removed by negative events
             */
        } catch (NonUniqueResultException nure) {
            // serious bug in the processing logic
            log.debug("Found multiple unmatched results for alertConditionId of " + alertConditionId
                + " while performing deactivation.  There should only be one.");
        }
    }

    public void checkForCompletedAlertConditionSet(int alertConditionId) {
        Integer alertDefinitionId = alertConditionManager
            .getAlertDefinitionByConditionIdInNewTransaction(alertConditionId);

        // ok, so figure out whether all of the conditions have been met
        boolean conditionSetResult = evaluateConditionSet(alertDefinitionId);

        log.debug("Alert definition with conditionId=" + alertConditionId + " evaluated to " + conditionSetResult);
        /*
         * The AlertDampeningEvents keep a running log of when all conditions have become true, as well as when they
         * become untrue (if they were most recently known to be true)
         */
        AlertDampeningEvent latestEvent = alertDampeningManager.getLatestEventByAlertDefinitionId(alertDefinitionId);
        AlertDampeningEvent.Type type = getNextEventType(latestEvent, conditionSetResult);
        log.debug("Latest event was " + latestEvent + ", " + "next AlertDampeningEvent.Type is " + type);

        /*
         * Finally, operate on the new type event
         */
        if (type != AlertDampeningEvent.Type.UNCHANGED) {
            /*
             * But only if it represents a type of event we need to act on
             */
            AlertDefinition flyWeightDefinition = new AlertDefinition();
            flyWeightDefinition.setId(alertDefinitionId);
            AlertDampeningEvent alertDampeningEvent = new AlertDampeningEvent(flyWeightDefinition, type);
            entityManager.persist(alertDampeningEvent);

            log.debug("Need to process AlertDampeningEvent.Type of " + type + " " + "for AlertDefinition[ id="
                + alertDefinitionId + " ]");

            alertDampeningManager.processEventType(alertDefinitionId, type);
        }
    }

    private AlertDampeningEvent.Type getNextEventType(AlertDampeningEvent lastEvent, boolean conditionSetResult) {
        /*
         * We always want to fire in the positive case.  This will give us the ability to compute both time-span and
         * count dampening categories using the same data set.
         */
        if (conditionSetResult) {
            /*
             * If lastEvent was null, we have no events for this AlertDefinition yet, and if we're in the negative state
             * that we've been in the positive state once before; In both cases, we're moving to the positive state.
             */
            if ((lastEvent == null) || (lastEvent.getEventType() == AlertDampeningEvent.Type.NEGATIVE)) {
                return AlertDampeningEvent.Type.POSITIVE;
            }

            /*
             * However, we want to add a POSITIVE_AGAIN event type to the history if we know the last event was already
             * either POSITIVE or POSITIVE_AGAIN.
             */
            else if ((lastEvent.getEventType() == AlertDampeningEvent.Type.POSITIVE)
                || (lastEvent.getEventType() == AlertDampeningEvent.Type.POSITIVE_AGAIN)) {
                return AlertDampeningEvent.Type.POSITIVE_AGAIN;
            }

            /*
             * for new functionality, make sure the callers recognize that this method needs to be expanded to support
             * the new AlertDampeningEvent.Type
             */
            else {
                throw new RuntimeException("Threshold reached, but AlertDampenintEvent.Type '"
                    + lastEvent.getEventType() + " not supported.");
            }
        }

        /*
         * Our unmatched logs don't match the expected count, thus our condition set isn't true as a whole due to the
         * most recent event; thus, we must check to see whether we need to terminate an open-ended interval or not.
         */
        else {
            /*
             * special handling to represent that we're already in the negative state, and to suppress sending an
             * AlertDampeningEventMessage to the alertNotificationProducer altogether
             */
            if ((lastEvent == null) || (lastEvent.getEventType() == AlertDampeningEvent.Type.NEGATIVE)) {
                return AlertDampeningEvent.Type.UNCHANGED;
            }

            /*
             * here, we were currently in one of the two positive states, so go to the negative state
             */
            else if ((lastEvent.getEventType() == AlertDampeningEvent.Type.POSITIVE)
                || (lastEvent.getEventType() == AlertDampeningEvent.Type.POSITIVE_AGAIN)) {
                return AlertDampeningEvent.Type.NEGATIVE;
            }

            /*
             * for new functionality, make sure the callers recognize that this method needs to be expanded to support
             * the new AlertDampeningEvent.Type
             */
            else {
                throw new RuntimeException("Threshold missed, but AlertDampenintEvent.Type '"
                    + lastEvent.getEventType() + " not supported.");
            }
        }
    }

    private boolean evaluateConditionSet(Integer alertDefinitionId) {
        List<AlertConditionLog> unmatchedLogs = this.getUnmatchedLogsByAlertDefinitionId(alertDefinitionId);

        BooleanExpression expression = alertConditionLogManager.getConditionExpression(alertDefinitionId);

        if (expression == BooleanExpression.ANY) {
            if (log.isDebugEnabled()) {
                int conditionSetSize = alertConditionLogManager.getConditionCount(alertDefinitionId);
                log.debug("Need only 1 of " + conditionSetSize + " conditions to be true, " + "found "
                    + unmatchedLogs.size() + " for AlertDefinition[id=" + alertDefinitionId + "]");
            }
            return (unmatchedLogs.size() > 0);
        } else if (expression == BooleanExpression.ALL) {
            int conditionSetSize = alertConditionLogManager.getConditionCount(alertDefinitionId);
            if (log.isDebugEnabled()) {
                log.debug("Need all " + conditionSetSize + " conditions to be true, " + "found " + unmatchedLogs.size()
                    + " for AlertDefinition[id=" + alertDefinitionId + "]");
            }
            return (unmatchedLogs.size() == conditionSetSize);
        } else {
            if (log.isDebugEnabled()) {
                log.error("AlertConditionLogManager does not support " + expression + " boolean expression yet");
            }
            return false;
        }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public BooleanExpression getConditionExpression(int alertDefinitionId) {
        Query query = entityManager.createQuery("" //
            + "SELECT ad.conditionExpression " //
            + "  FROM AlertDefinition ad " //
            + " WHERE ad.id = :alertDefinitionId");
        query.setParameter("alertDefinitionId", alertDefinitionId);
        BooleanExpression expression = (BooleanExpression) query.getSingleResult();
        return expression;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public int getConditionCount(int alertDefinitionId) {
        Query query = entityManager.createQuery("" //
            + "SELECT COUNT(ac) " //
            + "  FROM AlertDefinition ad " //
            + "  JOIN ad.conditions ac " //
            + " WHERE ad.id = :alertDefinitionId");
        query.setParameter("alertDefinitionId", alertDefinitionId);
        long conditionCount = (Long) query.getSingleResult();
        return (int) conditionCount;
    }
}