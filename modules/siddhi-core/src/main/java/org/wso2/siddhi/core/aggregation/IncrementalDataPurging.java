/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.aggregation;

import org.apache.log4j.Logger;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.MetaStateEvent;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventPool;
import org.wso2.siddhi.core.exception.DataPurgingException;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.table.Table;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.collection.operator.CompiledCondition;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import org.wso2.siddhi.query.api.aggregation.TimePeriod;
import org.wso2.siddhi.query.api.annotation.Annotation;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.definition.AggregationDefinition;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.TableDefinition;
import org.wso2.siddhi.query.api.expression.Expression;
import org.wso2.siddhi.query.api.expression.Variable;
import org.wso2.siddhi.query.api.expression.condition.Compare;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.wso2.siddhi.query.api.expression.Expression.Time.normalizeDuration;
import static org.wso2.siddhi.query.api.expression.Expression.Time.timeToLong;

/**
 * This class implements the logic which is needed to purge data which are related to incremental
 **/
public class IncrementalDataPurging implements Runnable {
    private static final Logger LOG = Logger.getLogger(IncrementalDataPurging.class);
    private long purgeExecutionInterval = Expression.Time.minute(15).value();
    private boolean purgingEnabled = true;
    private Map<TimePeriod.Duration, Long> retentionPeriods = new EnumMap<>(TimePeriod.Duration.class);
    private StreamEventPool streamEventPool;
    private Map<TimePeriod.Duration, Table> aggregationTables;
    private SiddhiAppContext siddhiAppContext;
    private Map<String, Table> tableMap;
    private static ScheduledFuture scheduledPurgingTaskStatus;
    private static final String INTERNAL_AGG_TIMESTAMP_FIELD = "AGG_TIMESTAMP";
    private static final Long RETAIN_ALL = -1L;
    private ComplexEventChunk<StateEvent> eventChunk = new ComplexEventChunk<>(true);
    private List<VariableExpressionExecutor> variableExpressionExecutorList = new ArrayList<>();
    private Attribute aggregatedTimestampAttribute;
    private VariableExpressionExecutor variableExpressionExecutor;


    public void init(AggregationDefinition aggregationDefinition, StreamEventPool streamEventPool,
                     Map<TimePeriod.Duration, Table> aggregationTables, SiddhiAppContext siddhiAppContext,
                     Map<String, Table> tableMap) {
        this.siddhiAppContext = siddhiAppContext;
        List<Annotation> annotations = aggregationDefinition.getAnnotations();
        this.tableMap = tableMap;
        this.streamEventPool = streamEventPool;
        this.aggregationTables = aggregationTables;

        aggregatedTimestampAttribute = new Attribute(INTERNAL_AGG_TIMESTAMP_FIELD, Attribute.Type.LONG);
        variableExpressionExecutor = new VariableExpressionExecutor(aggregatedTimestampAttribute, 0, 1);
        variableExpressionExecutorList.add(variableExpressionExecutor);
        for (TimePeriod.Duration duration : aggregationTables.keySet()) {
            switch (duration) {
                case SECONDS:
                    retentionPeriods.put(duration, Expression.Time.sec(30).value());
                    break;
                case MINUTES:
                    retentionPeriods.put(duration, Expression.Time.hour(24).value());
                    break;
                case HOURS:
                    retentionPeriods.put(duration, Expression.Time.day(30).value());
                    break;
                case DAYS:
                    retentionPeriods.put(duration, Expression.Time.year(5).value());
                    break;
                case MONTHS:
                    retentionPeriods.put(duration, RETAIN_ALL);
                    break;
                case YEARS:
                    retentionPeriods.put(duration, RETAIN_ALL);
            }
        }

        Map<String, Annotation> annotationTypes = new HashMap<>();

        for (Annotation annotation : annotations) {
            annotationTypes.put(annotation.getName().toLowerCase(), annotation);
        }
        Annotation purge = annotationTypes.get(SiddhiConstants.NAMESPACE_PURGE);
        if (Objects.nonNull(purge)) {

            if (Objects.nonNull(purge.getElement(SiddhiConstants.ANNOTATION_ELEMENT_ENABLE))) {
                String enable = purge.getElement(SiddhiConstants.ANNOTATION_ELEMENT_ENABLE);
                if (!("true".equalsIgnoreCase(enable) || "false".equalsIgnoreCase(enable))) {
                    throw new SiddhiAppCreationException("Undefined value for enable: " + enable + "." +
                            " Please use true or false");
                } else {
                    purgingEnabled = Boolean.parseBoolean(enable);
                }
            }
            if (purgingEnabled) {
                // If interval is defined, default value of 15 min will be replaced by user input value
                if (Objects.nonNull(purge.getElement(SiddhiConstants.NAMESPACE_INTERVAL))) {
                    String interval = purge.getElement(SiddhiConstants.NAMESPACE_INTERVAL);
                    purgeExecutionInterval = timeToLong(interval);
                }
                List<Annotation> retentions = purge.getAnnotations(SiddhiConstants.NAMESPACE_RETENTION);
                if (Objects.nonNull(retentions) && !retentions.isEmpty()) {
                    Annotation retention = retentions.get(0);
                    List<Element> elements = retention.getElements();
                    for (Element element : elements) {
                        TimePeriod.Duration duration = normalizeDuration(element.getKey());
                        if (!aggregationTables.keySet().contains(duration)) {
                            throw new DataPurgingException(duration + " granularity cannot be purged since " +
                                    "aggregation has not performed in " + duration + " granularity");
                        }
                        if (element.getValue().equalsIgnoreCase("all")) {
                            retentionPeriods.put(duration, RETAIN_ALL);
                        } else {
                            retentionPeriods.put(duration, timeToLong(element.getValue()));
                        }
                    }
                }
            }
        }
    }

    private Long getPurgeExecutionInterval() {
        return purgeExecutionInterval;
    }

    public boolean isPurgingEnabled() {
        return purgingEnabled;
    }

    @Override
    public void run() {
        long currentTime = System.currentTimeMillis();
        long purgeTime;
        Object[] purgeTimes = new Object[1];
        BaseIncrementalDataPurgingValueStore baseIncrementalDataPurgingValueStore = new
                BaseIncrementalDataPurgingValueStore(currentTime, streamEventPool);

        for (Map.Entry<TimePeriod.Duration, Table> entry : aggregationTables.entrySet()) {
            if (retentionPeriods.get(entry.getKey()) != RETAIN_ALL) {
                Variable leftVariable = new Variable(INTERNAL_AGG_TIMESTAMP_FIELD);
                leftVariable.setStreamId(entry.getValue().getTableDefinition().getId());
                Compare expression = new Compare(leftVariable,
                        Compare.Operator.LESS_THAN, new Variable(INTERNAL_AGG_TIMESTAMP_FIELD));
                purgeTime = currentTime - retentionPeriods.get(entry.getKey());
                purgeTimes[0] = purgeTime;
                StateEvent secEvent = baseIncrementalDataPurgingValueStore.createStreamEvent(purgeTimes);
                eventChunk.add(secEvent);
                Table table = aggregationTables.get(entry.getKey());
                try {
                    CompiledCondition compiledCondition = table.compileCondition(expression,
                            matchingMetaInfoHolder(table, aggregatedTimestampAttribute), siddhiAppContext,
                            variableExpressionExecutorList,
                            tableMap, table.getTableDefinition().getId() + "DeleteQuery");
                    LOG.info("Purging data of table: " + table.getTableDefinition().getId() + " with a" +
                            " retention of timestamp : " + purgeTime);

                    table.deleteEvents(eventChunk, compiledCondition, 1);
                } catch (Exception e) {
                    LOG.error("Exception occurred while deleting events from " +
                            table.getTableDefinition().getId() + " table", e);
                    throw new DataPurgingException("Exception occurred while deleting events from " +
                            table.getTableDefinition().getId() + " table", e);
                }
            }
        }
    }

    /**
     * Building the MatchingMetaInfoHolder for delete records
     **/
    private MatchingMetaInfoHolder matchingMetaInfoHolder(Table table, Attribute attribute) {
        MetaStateEvent metaStateEvent = new MetaStateEvent(2);

        MetaStreamEvent metaStreamEventWithDeletePara = new MetaStreamEvent();
        MetaStreamEvent metaStreamEventForTable = new MetaStreamEvent();

        TableDefinition deleteTableDefinition = TableDefinition.id("");
        deleteTableDefinition.attribute(attribute.getName(), attribute.getType());
        metaStreamEventWithDeletePara.setEventType(MetaStreamEvent.EventType.TABLE);
        metaStreamEventWithDeletePara.addOutputData(attribute);
        metaStreamEventWithDeletePara.addInputDefinition(deleteTableDefinition);


        metaStreamEventForTable.setEventType(MetaStreamEvent.EventType.TABLE);
        for (Attribute attributes : table.getTableDefinition().getAttributeList()) {
            metaStreamEventForTable.addOutputData(attributes);
        }
        metaStreamEventForTable.addInputDefinition(table.getTableDefinition());

        metaStateEvent.addEvent(metaStreamEventWithDeletePara);
        metaStateEvent.addEvent(metaStreamEventForTable);

        TableDefinition definition = table.getTableDefinition();
        return new MatchingMetaInfoHolder(metaStateEvent,
                0, 1, deleteTableDefinition, definition, 0);
    }

    /**
     * Data purging task scheduler method
     **/
    public static void executeIncrementalDataPurging(SiddhiAppContext siddhiAppContext,
                                                     IncrementalDataPurging incrementalDataPurging) {

        if (Objects.nonNull(scheduledPurgingTaskStatus)) {
            scheduledPurgingTaskStatus.cancel(true);
            scheduledPurgingTaskStatus = siddhiAppContext.getScheduledExecutorService().
                    scheduleWithFixedDelay(incrementalDataPurging, incrementalDataPurging.getPurgeExecutionInterval(),
                            incrementalDataPurging.getPurgeExecutionInterval(),
                            TimeUnit.MILLISECONDS);
        } else {
            scheduledPurgingTaskStatus = siddhiAppContext.getScheduledExecutorService().
                    scheduleWithFixedDelay(incrementalDataPurging, incrementalDataPurging.getPurgeExecutionInterval(),
                            incrementalDataPurging.getPurgeExecutionInterval(),
                            TimeUnit.MILLISECONDS);
        }
    }
}

