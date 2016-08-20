/*
 * Copyright 2015-2016 Jeeva Kandasamy (jkandasa@gmail.com)
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mycontroller.standalone.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.script.ScriptException;

import org.mycontroller.standalone.AppProperties;
import org.mycontroller.standalone.AppProperties.RESOURCE_TYPE;
import org.mycontroller.standalone.api.jaxrs.json.McHeatMap;
import org.mycontroller.standalone.db.DaoUtils;
import org.mycontroller.standalone.db.tables.MetricsBatteryUsage;
import org.mycontroller.standalone.db.tables.MetricsBinaryTypeDevice;
import org.mycontroller.standalone.db.tables.MetricsCounterTypeDevice;
import org.mycontroller.standalone.db.tables.MetricsDoubleTypeDevice;
import org.mycontroller.standalone.db.tables.Node;
import org.mycontroller.standalone.db.tables.SensorVariable;
import org.mycontroller.standalone.exceptions.McBadRequestException;
import org.mycontroller.standalone.metrics.MetricDouble;
import org.mycontroller.standalone.metrics.MetricsUtils.METRIC_TYPE;
import org.mycontroller.standalone.model.ResourceCountModel;
import org.mycontroller.standalone.model.ResourceModel;
import org.mycontroller.standalone.scripts.McScript;
import org.mycontroller.standalone.scripts.McScriptEngine;
import org.mycontroller.standalone.scripts.McScriptException;
import org.mycontroller.standalone.settings.MetricsDataRetentionSettings;
import org.mycontroller.standalone.utils.McUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * @author Jeeva Kandasamy (jkandasa)
 * @since 0.0.3
 */
@Slf4j
public class MetricApi {
    //Get count of resources
    public ResourceCountModel getResourceCount(RESOURCE_TYPE resourceType, Integer resourceId) {
        return new ResourceCountModel(resourceType, resourceId);
    }

    public List<MetricsDoubleTypeDevice> getSensorVariableMetricsDouble(Integer sensorVariableId,
            Long timestampFrom, Long timestampTo, String bucketDurationString) {
        timestampFrom = getTimestampFrom(timestampFrom);
        timestampTo = getTimestampTo(timestampTo);
        List<MetricsDoubleTypeDevice> metricsFinal = new ArrayList<MetricsDoubleTypeDevice>();
        long bucketDuration = getBucketDuration(bucketDurationString);
        _logger.debug("timestamp:[from:{}, to:{}], bucketDurationString:{}, bucketDuration:{}, totalDuration:{}",
                timestampFrom, timestampTo, bucketDurationString, bucketDuration, (timestampTo - timestampFrom));
        if ((timestampTo - timestampFrom) < bucketDuration) {
            return metricsFinal;
        }
        MetricsDoubleTypeDevice metricConfig = MetricsDoubleTypeDevice.builder()
                .sensorVariable(SensorVariable.builder().id(sensorVariableId).build())
                .build();
        Long tmpTimestampTo = timestampFrom + bucketDuration;
        while (tmpTimestampTo < timestampTo) {
            metricConfig.setTimestampFrom(timestampFrom);
            timestampFrom = tmpTimestampTo;
            if (tmpTimestampTo > timestampTo) {
                break;
            }
            metricConfig.setTimestampTo(tmpTimestampTo);
            tmpTimestampTo += bucketDuration;
            List<MetricsDoubleTypeDevice> metrics = DaoUtils.getMetricsDoubleTypeDeviceDao().getAll(metricConfig);
            if (metrics != null && !metrics.isEmpty()) {
                Double min = Double.MAX_VALUE;  //Possible positive highest double value
                Double max = Double.NEGATIVE_INFINITY;//Take lowest double number, MIN_VALUE, doesn't work.
                Double sum = 0D;
                int samples = 0;
                for (MetricsDoubleTypeDevice metric : metrics) {
                    //calculate with max, min, avg and previous samples
                    if (metric.getMax() == null) {
                        metric.setMax(metric.getAvg());
                        metric.setMin(metric.getAvg());
                    }
                    if (metric.getMax() > max) {
                        max = metric.getMax();
                    }

                    if (metric.getMin() < min) {
                        min = metric.getMin();
                    }
                    sum = sum + (metric.getAvg() * metric.getSamples());
                    samples = samples + metric.getSamples();

                }
                Double avg = sum / samples;
                metricsFinal.add(MetricsDoubleTypeDevice.builder()
                        .sensorVariable(metricConfig.getSensorVariable())
                        .min(McUtils.round(min, McUtils.DOUBLE_ROUND))
                        .max(McUtils.round(max, McUtils.DOUBLE_ROUND))
                        .avg(McUtils.round(avg, McUtils.DOUBLE_ROUND))
                        .samples(samples)
                        .timestamp(metricConfig.getTimestampTo())
                        .build());
            }
        }
        return metricsFinal;
    }

    public MetricDouble getSensorVariableMetricDouble(SensorVariable sensorVariable, Long timestampFrom,
            Long timestampTo) {
        MetricsDoubleTypeDevice queryInput = MetricsDoubleTypeDevice.builder()
                .timestampFrom(timestampFrom)
                .timestampTo(timestampTo)
                .sensorVariable(sensorVariable)
                .build();

        MetricsDoubleTypeDevice metric = DaoUtils.getMetricsDoubleTypeDeviceDao().getMinMaxAvg(queryInput);

        MetricDouble metricDouble = null;
        //If current value not available, do not allow any value
        if (sensorVariable.getValue() == null || metric.getMin() == null) {
            metricDouble = MetricDouble.builder().variable(sensorVariable).build();
        } else {
            metricDouble = MetricDouble.builder()
                    .variable(sensorVariable)
                    .minimum(metric.getMin())
                    .average(metric.getAvg())
                    .maximum(metric.getMax())
                    .current(McUtils.getDouble(sensorVariable.getValue()))
                    .previous(McUtils.getDouble(sensorVariable.getPreviousValue()))
                    .build();
        }
        return metricDouble;
    }

    public List<MetricsCounterTypeDevice> getSensorVariableMetricsCounter(Integer sensorVariableId,
            Long timestampFrom, Long timestampTo, String bucketDurationString) {
        timestampFrom = getTimestampFrom(timestampFrom);
        timestampTo = getTimestampTo(timestampTo);
        List<MetricsCounterTypeDevice> metricsFinal = new ArrayList<MetricsCounterTypeDevice>();
        _logger.debug("timestamp:[from:{}, to:{}], bucketDurationString:{}",
                timestampFrom, timestampTo, bucketDurationString);
        MetricsCounterTypeDevice metricConfig = MetricsCounterTypeDevice.builder()
                .sensorVariable(SensorVariable.builder().id(sensorVariableId).build())
                .build();
        Calendar calendarFrom = Calendar.getInstance();
        Calendar calendarTo = Calendar.getInstance();
        calendarFrom.setTime(new Date(timestampFrom));
        calendarTo.setTime(new Date(timestampTo));
        String[] bucket = bucketDurationString.trim().split("(?<=\\d)(?=\\D)");
        if (bucket.length != 2) {
            _logger.warn("Invalid bucketDuration string: {}, result:{}", bucketDurationString, bucket);
            return metricsFinal;
        }
        Integer increment = McUtils.getInteger(bucket[0]);
        Integer incrementRef = null;
        String bucketString = bucket[1].toLowerCase();

        switch (bucketString) {
            case "m":
                calendarFrom.set(Calendar.DAY_OF_MONTH, 1);
                calendarTo.set(Calendar.DAY_OF_MONTH, 1);
                incrementRef = Calendar.MONTH;
            case "d":
                calendarFrom.set(Calendar.HOUR_OF_DAY, 0);
                calendarTo.set(Calendar.HOUR_OF_DAY, 0);
                if (incrementRef == null) {
                    incrementRef = Calendar.DATE;
                }
            case "h":
                calendarFrom.set(Calendar.MINUTE, 0);
                calendarTo.set(Calendar.MINUTE, 0);
                if (incrementRef == null) {
                    incrementRef = Calendar.HOUR;
                }
            case "mn":
                calendarFrom.set(Calendar.MILLISECOND, 0);
                calendarTo.set(Calendar.MILLISECOND, 0);
                calendarFrom.set(Calendar.SECOND, 0);
                calendarTo.set(Calendar.SECOND, 0);
                if (incrementRef == null) {
                    incrementRef = Calendar.MINUTE;
                }
        }
        while (calendarFrom.before(calendarTo) || calendarFrom.equals(calendarTo)) {
            metricConfig.setTimestampFrom(calendarFrom.getTimeInMillis());
            calendarFrom.add(incrementRef, increment);
            metricConfig.setTimestampTo(calendarFrom.getTimeInMillis());
            List<MetricsCounterTypeDevice> metrics = DaoUtils.getMetricsCounterTypeDeviceDao().getAll(metricConfig);
            if (metrics != null && !metrics.isEmpty()) {
                Long sum = 0L;
                int samples = 0;
                for (MetricsCounterTypeDevice metric : metrics) {
                    sum = sum + metric.getValue();
                }
                if ((bucketString.equals("mn") || bucketString.equals("h"))
                        && metricConfig.getTimestampTo() > System.currentTimeMillis()) {
                    break;
                }
                metricsFinal.add(MetricsCounterTypeDevice.builder()
                        .sensorVariable(metricConfig.getSensorVariable())
                        .value(sum)
                        .samples(samples)
                        .timestamp(metricConfig.getTimestampTo() - 1L)//Remove one millisecond from actual timestamp
                        .build());
            }
        }
        return metricsFinal;
    }

    public List<MetricsBinaryTypeDevice> getSensorVariableMetricsBinary(Integer sensorVariableId,
            Long timestampFrom, Long timestampTo) {
        return DaoUtils.getMetricsBinaryTypeDeviceDao().getAll(MetricsBinaryTypeDevice.builder()
                .timestampFrom(timestampFrom)
                .timestampTo(timestampTo)
                .sensorVariable(SensorVariable.builder().id(sensorVariableId).build())
                .build());
    }

    public List<MetricsBatteryUsage> getMetricsBattery(Integer nodeId, Long timestampFrom, Long timestampTo,
            Boolean withMinMax, String bucketDurationString) {
        timestampFrom = getTimestampFrom(timestampFrom);
        timestampTo = getTimestampTo(timestampTo);
        List<MetricsBatteryUsage> metricsFinal = new ArrayList<MetricsBatteryUsage>();
        long bucketDuration = getBucketDuration(bucketDurationString);
        _logger.debug("timestamp:[from:{}, to:{}], bucketDurationString:{}, bucketDuration:{}, totalDuration:{}",
                timestampFrom, timestampTo, bucketDurationString, bucketDuration, (timestampTo - timestampFrom));
        if ((timestampTo - timestampFrom) < bucketDuration) {
            return metricsFinal;
        }
        MetricsBatteryUsage metricConfig = MetricsBatteryUsage.builder()
                .node(Node.builder().id(nodeId).build())
                .build();
        Long tmpTimestampTo = timestampFrom + bucketDuration;
        while (tmpTimestampTo < timestampTo) {
            metricConfig.setTimestampFrom(timestampFrom);
            timestampFrom = tmpTimestampTo;
            if (tmpTimestampTo > timestampTo) {
                break;
            }
            metricConfig.setTimestampTo(tmpTimestampTo);
            tmpTimestampTo += bucketDuration;
            List<MetricsBatteryUsage> metrics = DaoUtils.getMetricsBatteryUsageDao().getAll(metricConfig);
            if (metrics != null && !metrics.isEmpty()) {
                Double min = Double.MAX_VALUE;  //Possible positive highest double value
                Double max = Double.NEGATIVE_INFINITY;//Take lowest double number, MIN_VALUE, doesn't work.
                Double sum = 0D;
                int samples = 0;
                for (MetricsBatteryUsage metric : metrics) {
                    //calculate with max, min, avg and previous samples
                    if (metric.getMax() == null) {
                        metric.setMax(metric.getAvg());
                        metric.setMin(metric.getAvg());
                    }
                    if (metric.getMax() > max) {
                        max = metric.getMax();
                    }

                    if (metric.getMin() < min) {
                        min = metric.getMin();
                    }
                    sum = sum + (metric.getAvg() * metric.getSamples());
                    samples = samples + metric.getSamples();

                }
                Double avg = sum / samples;
                metricsFinal.add(MetricsBatteryUsage.builder()
                        .node(metricConfig.getNode())
                        .min(McUtils.round(min, McUtils.DOUBLE_ROUND))
                        .max(McUtils.round(max, McUtils.DOUBLE_ROUND))
                        .avg(McUtils.round(avg, McUtils.DOUBLE_ROUND))
                        .samples(samples)
                        .timestamp(metricConfig.getTimestampTo())
                        .build());
            }
        }
        return metricsFinal;
    }

    public List<McHeatMap> getHeatMapNodeBatteryLevel(List<Integer> nodeIds) {
        List<McHeatMap> mcHeatMap = new ArrayList<McHeatMap>();
        List<Node> nodes = null;
        if (nodeIds != null) {
            nodes = DaoUtils.getNodeDao().getAll(nodeIds);
        } else {
            nodes = DaoUtils.getNodeDao().getAll();
        }
        for (Node node : nodes) {
            if (node.getBatteryLevel() != null) {
                mcHeatMap.add(McHeatMap.builder()
                        .id(node.getId().longValue())
                        .altId(node.getId().longValue())
                        .value(McUtils.round((McUtils.getDouble(node.getBatteryLevel()) / 100.0), 2))
                        .tooltip(new ResourceModel(RESOURCE_TYPE.NODE, node).getResourceLessDetails())
                        .build());
            }
        }
        return mcHeatMap;
    }

    public List<McHeatMap> getHeatMapNodeState(List<Integer> nodeIds) {
        List<McHeatMap> mcHeatMap = new ArrayList<McHeatMap>();
        List<Node> nodes = null;
        if (nodeIds != null) {
            nodes = DaoUtils.getNodeDao().getAll(nodeIds);
        } else {
            nodes = DaoUtils.getNodeDao().getAll();
        }
        Double value = null;
        for (Node node : nodes) {
            value = null;
            switch (node.getState()) {
                case DOWN:
                    value = 0.0;
                    break;
                case UNAVAILABLE:
                    value = 0.5;
                    break;
                case UP:
                    value = 1.0;
                    break;
                default:
                    break;
            }
            mcHeatMap.add(McHeatMap.builder()
                    .id(node.getId().longValue())
                    .altId(node.getId().longValue())
                    .value(value)
                    .tooltip(new ResourceModel(RESOURCE_TYPE.NODE, node).getResourceLessDetails())
                    .build());
        }
        return mcHeatMap;
    }

    public List<McHeatMap> getHeatMapSensorVariableDouble(List<Integer> svIds, Double upperLimit) {
        if (upperLimit == null) {
            upperLimit = 100.0;
        }
        List<McHeatMap> mcHeatMap = new ArrayList<McHeatMap>();
        List<SensorVariable> sVariables = null;
        if (svIds != null) {
            sVariables = DaoUtils.getSensorVariableDao().getAll(svIds);
        } else {
            sVariables = DaoUtils.getSensorVariableDao().getAll(SensorVariable.KEY_METRIC, METRIC_TYPE.DOUBLE);
        }
        Double value = null;
        for (SensorVariable sVariable : sVariables) {
            if (sVariable.getValue() != null) {
                value = McUtils.round(McUtils.getDouble(sVariable.getValue()) / upperLimit, 2);
                mcHeatMap.add(McHeatMap.builder()
                        .id(sVariable.getId().longValue())
                        .altId(sVariable.getSensor().getId().longValue())
                        .value(value)
                        .tooltip(new ResourceModel(RESOURCE_TYPE.SENSOR_VARIABLE, sVariable).getResourceLessDetails())
                        .build());
            }
        }
        return mcHeatMap;
    }

    @SuppressWarnings("unchecked")
    public List<McHeatMap> getHeatMapScript(String scriptName) throws McBadRequestException, IllegalAccessException,
            IOException, McScriptException, ScriptException {
        if (scriptName == null) {
            throw new McBadRequestException("script file name missing!");
        }
        McScriptEngine mcScriptEngine = new McScriptEngine(McScript.getMcScript(scriptName));
        return (List<McHeatMap>) mcScriptEngine.executeScript();
    }

    public static long getBucketDuration(String bucketDuration) {
        if (bucketDuration == null) {
            return McUtils.ONE_MINUTE;
        } else if (bucketDuration.endsWith("mn")) {
            return McUtils.getLong(bucketDuration.replace("mn", "")) * McUtils.ONE_MINUTE;
        } else if (bucketDuration.endsWith("h")) {
            return McUtils.getLong(bucketDuration.replace("h", "")) * McUtils.ONE_HOUR;
        } else if (bucketDuration.endsWith("d")) {
            return McUtils.getLong(bucketDuration.replace("d", "")) * McUtils.ONE_DAY;
        } else if (bucketDuration.endsWith("m")) {
            return McUtils.getLong(bucketDuration.replace("m", "")) * McUtils.ONE_DAY * 30;
        }
        return McUtils.ONE_MINUTE;
    }

    public static Long getTimestampFrom(Long timestampFrom) {
        if (timestampFrom == null) {
            timestampFrom = System.currentTimeMillis() - McUtils.HOUR * 6;
        }
        return timestampFrom;
    }

    public static Long getTimestampTo(Long timestampTo) {
        if (timestampTo == null) {
            timestampTo = System.currentTimeMillis();
        }
        return timestampTo;
    }

    public static String getBucketDuration(Long timestampFrom, Long timestampTo, METRIC_TYPE metricType) {
        timestampFrom = getTimestampFrom(timestampFrom);
        timestampTo = getTimestampTo(timestampTo);
        Long duration = timestampTo - timestampFrom;
        if (metricType == METRIC_TYPE.DOUBLE) {
            MetricsDataRetentionSettings metricSettings = AppProperties.getInstance()
                    .getMetricsDataRetentionSettings();
            if (duration > metricSettings.getRetentionTwelveHours()) {
                return "1d";
            } else if (duration > metricSettings.getRetentionSixHours()) {
                return "12h";
            } else if (duration > metricSettings.getRetentionOneHour()) {
                return "6h";
            } else if (duration > metricSettings.getRetentionFiveMinutes()) {
                return "1h";
            } else if (duration > metricSettings.getRetentionOneMinute()) {
                return "5mn";
            } else if (duration > metricSettings.getRetentionRawData()) {
                return "1mn";
            }
        } else if (metricType == METRIC_TYPE.COUNTER) {
            if (duration >= McUtils.DAY * 29) {
                return "1m";
            } else if (duration >= McUtils.DAY * 6) {
                return "1d";
            } else if (duration >= McUtils.ONE_HOUR * 23) {
                return "30mn";
            } else if (duration >= McUtils.ONE_HOUR * 11) {
                return "15mn";
            } else if (duration >= McUtils.ONE_HOUR * 5) {
                return "5mn";
            } else if (duration >= McUtils.ONE_MINUTE) {
                return "1mn";
            }
        }
        return "1mn";
    }

}
