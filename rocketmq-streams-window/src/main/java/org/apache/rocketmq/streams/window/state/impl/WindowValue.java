/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.streams.window.state.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.rocketmq.streams.common.context.IMessage;
import org.apache.rocketmq.streams.common.context.Message;
import org.apache.rocketmq.streams.common.datatype.DataType;
import org.apache.rocketmq.streams.common.datatype.NotSupportDataType;
import org.apache.rocketmq.streams.common.interfaces.ISerialize;
import org.apache.rocketmq.streams.common.interfaces.IStreamOperator;
import org.apache.rocketmq.streams.common.utils.Base64Utils;
import org.apache.rocketmq.streams.common.utils.DataTypeUtil;
import org.apache.rocketmq.streams.common.utils.DateUtil;
import org.apache.rocketmq.streams.common.utils.ReflectUtil;
import org.apache.rocketmq.streams.common.utils.SerializeUtil;
import org.apache.rocketmq.streams.common.utils.StringUtil;
import org.apache.rocketmq.streams.common.utils.TraceUtil;
import org.apache.rocketmq.streams.script.context.FunctionContext;
import org.apache.rocketmq.streams.script.operator.impl.AggregationScript;
import org.apache.rocketmq.streams.script.operator.impl.FunctionScript;
import org.apache.rocketmq.streams.script.service.IAccumulator;
import org.apache.rocketmq.streams.window.model.FunctionExecutor;
import org.apache.rocketmq.streams.window.model.WindowInstance;
import org.apache.rocketmq.streams.window.operator.AbstractWindow;
import org.apache.rocketmq.streams.window.state.WindowBaseValue;
import org.nustaq.serialization.FSTConfiguration;

public class WindowValue extends WindowBaseValue implements Serializable ,ISerialize{

    private static final long serialVersionUID = 1083444850264401338L;

    private static final Log LOG = LogFactory.getLog(WindowValue.class);

    /**
     * 如果做分组，设置分组
     */
    protected String groupBy = "0";

    /**
     * split id和max offset的映射关系
     */
    protected Map<String, String> maxOffset = new ConcurrentHashMap<>(16);

    /**
     * the result of aggregation column
     */
    private Map<String, Object> aggColumnResult = new ConcurrentHashMap<>(16);

    /**
     * the result of select column
     */
    protected Map<String, Object> computedColumnResult = new HashMap<>(16);

    protected transient Long lastUpdateTime;//used in session window，set last update time

    public WindowValue() {
        setGmtCreate(DateUtil.getCurrentTime());
        setGmtModified(DateUtil.getCurrentTime());
    }

    public WindowValue(WindowValue theValue) {
        this.startTime = theValue.getStartTime();
        this.endTime = theValue.getEndTime();
        this.fireTime = theValue.getFireTime();
        this.groupBy = theValue.getGroupBy();
    }

    /**
     * 计算结果序列化成json
     *
     * @return
     */
    public String getAggColumnResult() {
        JSONArray jsonArray = new JSONArray();

        Iterator<Entry<String, Object>> it = aggColumnResult.entrySet().iterator();
        while (it.hasNext()) {
            JSONObject jsonObject = new JSONObject();
            Entry<String, Object> entry = it.next();
            String functionName = entry.getKey();
            Object value = entry.getValue();
            jsonObject.put("function", functionName);
            if (value == null) {
                continue;
            }
            DataType dataType = DataTypeUtil.getDataTypeFromClass(value.getClass());
            boolean isBasicType = false;
            String jsonValue = null;
            if (!NotSupportDataType.class.isInstance(dataType)) {
                isBasicType = true;
                jsonValue = dataType.toDataJson(value);
                jsonObject.put("datatype", dataType.getDataTypeName());
            } else {
                isBasicType = false;
                jsonValue = ReflectUtil.serializeObject(value).toJSONString();
            }

            jsonObject.put("isBasic", isBasicType);
            jsonObject.put("result", jsonValue);
            jsonArray.add(jsonObject);
        }
        return encodeSQLContent(jsonArray.toJSONString());
    }

    /**
     * 还原计算结果
     *
     * @param jsonArrayStr
     */
    public void setAggColumnResult(String jsonArrayStr) {
        jsonArrayStr = decodeSQLContent(jsonArrayStr);
        if (jsonArrayStr == null) {
            return;
        }
        JSONArray functionResultJson = JSONArray.parseArray(jsonArrayStr);
        for (int i = 0; i < functionResultJson.size(); i++) {
            JSONObject jsonObject = functionResultJson.getJSONObject(i);
            String functionName = jsonObject.getString("function");
            Boolean isBasic = jsonObject.getBoolean("isBasic");
            String jsonValue = jsonObject.getString("result");
            Object value = null;
            if (isBasic) {
                String datatype = jsonObject.getString("datatype");
                DataType dataType = DataTypeUtil.getDataType(datatype);
                value = dataType.getData(jsonValue);
            } else {
                JSONObject objectJson = JSONObject.parseObject(jsonValue);
                value = ReflectUtil.deserializeObject(objectJson);
            }
            aggColumnResult.put(functionName, value);
        }
    }

    public void setAggColumnMap(Map<String, Object> aggColumnResult) {
        this.aggColumnResult = aggColumnResult;
    }

    public void setComputedColumnResult(String computedColumnResult) {
        computedColumnResult = decodeSQLContent(computedColumnResult);
        this.computedColumnResult = Message.parseObject(computedColumnResult);
    }

    public void putComputedColumnResult(Map<String, Object> msg) {
        this.computedColumnResult.putAll(msg);
    }

    public String getComputedColumnResult() {
        JSONObject object = null;
        if (JSONObject.class.isInstance(computedColumnResult)) {
            object = (JSONObject) computedColumnResult;
        } else {
            object = new JSONObject(computedColumnResult);
        }
        return encodeSQLContent(object.toJSONString());
    }

    public void setMaxOffset(String theOffset) {
        JSONObject object = JSONObject.parseObject(theOffset);
        Iterator<String> iterator = object.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            maxOffset.put(key, object.getString(key));
        }
    }

    public String getMaxOffset() {
        JSONObject object = new JSONObject();
        object.putAll(maxOffset);
        return object.toJSONString();
    }

    public Iterator<Entry<String, Object>> iteratorComputedColumnResult() {
        return computedColumnResult.entrySet().iterator();
    }

    public Object getComputedColumnResultByKey(String fieldName) {
        return computedColumnResult.get(fieldName);
    }

    public Object getAggColumnResultByKey(String fieldName) {
        return aggColumnResult.get(fieldName);
    }

    public void putAggColumnResult(String functionName, Object value) {
        aggColumnResult.put(functionName, value);
    }

    public void removeAggColumnResult(String functionName) {
        aggColumnResult.remove(functionName);
    }

    public Map<String, Object> getcomputedResult() {
        return this.computedColumnResult;
    }

    /**
     * @param window  all kinds of configurable information
     * @param message the consumed data
     */
    public boolean calculate(AbstractWindow window, IMessage message) {
        message.getMessageBody().put(AbstractWindow.WINDOW_START, startTime);
        message.getMessageBody().put(AbstractWindow.WINDOW_END, endTime);
        //每个计算节点对应一个consume split，如果多于一个queue的话，
        String queueId = message.getHeader().getQueueId();
        String offset = message.getHeader().getOffset();
        if (StringUtil.isEmpty(offset)) {
            offset = String.valueOf(System.currentTimeMillis());
        }
        String maxOffsetOfQueue = this.maxOffset.get(queueId);
        if (StringUtil.isEmpty(maxOffsetOfQueue)) {
            maxOffsetOfQueue = offset;
            this.maxOffset.put(queueId, maxOffsetOfQueue);
        } else {
            if (message.getHeader().greateThan(maxOffsetOfQueue)) {
                this.maxOffset.put(queueId, offset);
            } else {
                //如果比最大的offset 小或等于，则直接丢弃掉消息
                System.out.println("!!!!!!!!!!!!!!!!!!! has outOfOrder data."
                        + "queueId= " + queueId + ",maxOffsetOfQueue="+ maxOffsetOfQueue
                        + ",messageOffset=" + message.getHeader().getOffset());
                return false;
            }
        }
        try {
            this.lastUpdateTime = WindowInstance.getOccurTime(window, message);
            if (window.getReduceSerializeValue() != null) {
                JSONObject accumulator = null;
                if (computedColumnResult != null && JSONObject.class.isInstance(computedColumnResult)) {
                    accumulator = (JSONObject) computedColumnResult;
                }

                JSONObject result = window.getReducer().reduce(accumulator, message.getMessageBody());
                computedColumnResult = result;
                return true;
            }
            calFunctionColumn(window, message);

        } catch (Exception e) {
            throw new RuntimeException("failed in window value calculating",e);
        }

        //there is no need writing back to message

        return true;
    }

    protected static AtomicInteger SUM=new AtomicInteger(0);

    protected void calFunctionColumn(AbstractWindow window, IMessage message) {
        String introduction = (String)message.getMessageBody().getOrDefault(AggregationScript.INNER_AGGREGATION_COMPUTE_KEY, "");
        boolean isMultiAccumulate = AggregationScript.INNER_AGGREGATION_COMPUTE_MULTI.equals(introduction);
        if(isMultiAccumulate){
            WindowValue windowValue=message.getMessageBody().getObject(WindowValue.class.getName(),WindowValue.class);
            try {
               // windowValue= SerializeUtil.deserialize(windowValueJson,WindowValue.class);
            }catch (Exception e){
                throw new RuntimeException("window value deserializeObject error",e);
            }
            List<WindowValue> windowValues=new ArrayList<>();
            windowValues.add(this);
            windowValues.add(windowValue);
            WindowValue mergerWindowValue= WindowValue.mergeWindowValue(window,windowValues);
            this.computedColumnResult.putAll(mergerWindowValue.computedColumnResult);
            this.aggColumnResult.putAll(mergerWindowValue.aggColumnResult);
            return;
        }
        for (Entry<String, List<FunctionExecutor>> entry : window.getColumnExecuteMap().entrySet()) {
            String computedColumn = entry.getKey();
            List<FunctionExecutor> fifoQueue = entry.getValue();
            for (FunctionExecutor operator : fifoQueue) {
                String executorName = operator.getColumn();
                IStreamOperator<IMessage, List<IMessage>> executor = operator.getExecutor();
                if (executor instanceof AggregationScript) {
                    AggregationScript originAccScript = (AggregationScript) executor;
                    AggregationScript windowAccScript = originAccScript.clone();
                    Object accumulator = null;
                    if (aggColumnResult.containsKey(executorName)) {
                        accumulator = aggColumnResult.get(executorName);
                    } else {
                        IAccumulator director = AggregationScript.getAggregationFunction(
                            windowAccScript.getFunctionName());
                        accumulator = director.createAccumulator();
                        aggColumnResult.put(executorName, accumulator);
                    }
                    windowAccScript.setAccumulator(accumulator);
                    if(!isMultiAccumulate){
                        message.getMessageBody().put(AggregationScript.INNER_AGGREGATION_COMPUTE_KEY,
                            AggregationScript.INNER_AGGREGATION_COMPUTE_SINGLE);
                    }
                    FunctionContext context = new FunctionContext(message);
                    windowAccScript.doMessage(message, context);
                } else if (executor instanceof FunctionScript) {
                    FunctionContext context = new FunctionContext(message);
                    ((FunctionScript) executor).doMessage(message, context);
                }
            }
            //
            computedColumnResult.put(computedColumn, message.getMessageBody().get(computedColumn));
        }
        calProjectColumn(window, message);
    }

    protected void calProjectColumn(AbstractWindow window, IMessage message) {
        Map<String, String> constMap = window.getColumnProjectMap();
        for (Entry<String, String> entry : constMap.entrySet()) {
            String computedColumn = entry.getKey();
            String originColumn = entry.getValue();
            if (message.getMessageBody().containsKey(originColumn)) {
                computedColumnResult.put(computedColumn, message.getMessageBody().get(originColumn));
            } else {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("field:\t " + originColumn + " lost!");
                }
            }
        }
    }


    /**
     * merge the group which has the same group by value and different split id
     */
    public static WindowValue mergeWindowValue(AbstractWindow window, List<WindowValue> valueList) {
        WindowValue lastWindowValue = new WindowValue(valueList.get(0));
        lastWindowValue.setComputedColumnResult(valueList.get(0).getComputedColumnResult());
        //
        for (Entry<String, List<FunctionExecutor>> entry : window.getColumnExecuteMap().entrySet()) {
            String computedColumn = entry.getKey();
            IMessage message = new Message(new JSONObject());
            FunctionContext context = new FunctionContext(message);
            List<FunctionExecutor> executorList = entry.getValue();
            //column outside of the aggregation function should be calculated again!
            boolean needMergeComputation = false;
            for (FunctionExecutor info : executorList) {
                String column = info.getColumn();
                IStreamOperator<IMessage, List<IMessage>> engine = info.getExecutor();
                if (engine instanceof AggregationScript) {
                    AggregationScript origin = (AggregationScript) engine;
                    AggregationScript operator = origin.clone();
                    if (needMergeComputation) {
                        message.getMessageBody().put(AggregationScript.INNER_AGGREGATION_COMPUTE_KEY,
                            AggregationScript.INNER_AGGREGATION_COMPUTE_SINGLE);
                        operator.setAccumulator(operator.getDirector().createAccumulator());
                        operator.doMessage(message, context);
                    } else {
                        message.getMessageBody().put(AggregationScript.INNER_AGGREGATION_COMPUTE_KEY,
                            AggregationScript.INNER_AGGREGATION_COMPUTE_MULTI);
                        List actors = valueList.stream().map(
                            windowValue -> {
                                Object accumulator = null;
                                if (windowValue.aggColumnResult.containsKey(column)) {
                                    accumulator = windowValue.aggColumnResult.get(column);
                                } else {
                                    IAccumulator director = AggregationScript.getAggregationFunction(
                                        operator.getFunctionName());
                                    accumulator = director.createAccumulator();
                                    windowValue.aggColumnResult.put(column, accumulator);
                                }
                                return accumulator;
                             }).collect(
                            Collectors.toList());
                        operator.setAccumulator(operator.getDirector().createAccumulator());
                        operator.setAccumulators(actors);
                        lastWindowValue.aggColumnResult.put(column,operator.getAccumulator());
                        operator.doMessage(message, context);
                        needMergeComputation = true;
                    }
                } else if (engine instanceof FunctionScript) {
                    FunctionScript theScript = (FunctionScript) engine;
                    String[] parameters = theScript.getDependentParameters();
                    for (String parameter : parameters) {
                        if (!message.getMessageBody().containsKey(parameter) && lastWindowValue.computedColumnResult
                            .containsKey(parameter)) {
                            message.getMessageBody().put(parameter,
                                lastWindowValue.computedColumnResult.get(parameter));
                        }
                    }
                    if (needMergeComputation) {
                        engine.doMessage(message, context);
                    }
                }
            }
            if (message.getMessageBody().containsKey(computedColumn)) {
                lastWindowValue.computedColumnResult.put(computedColumn, message.getMessageBody().get(computedColumn));
            } else if (!needMergeComputation) {
                lastWindowValue.computedColumnResult.put(computedColumn,
                    valueList.get(0).computedColumnResult.get(computedColumn));
            }
        }
        // valueList.stream().map(value -> lastWindowValue.count += value.getCount());
        //
        List<String> traceList = new ArrayList<>();
        for (WindowValue value : valueList) {
            if (value.computedColumnResult.containsKey(TraceUtil.TRACE_ID_FLAG)) {
                String traceIds = String.valueOf(value.computedColumnResult.get(TraceUtil.TRACE_ID_FLAG));
                traceList.addAll(Arrays.asList(traceIds.split(",")));
            }
        }
        if (!traceList.isEmpty()) {
            StringBuffer buffer = new StringBuffer();
            for (int i = 0; i < traceList.size(); i++) {
                buffer.append(traceList.get(i));
                if (i != traceList.size() - 1) {
                    buffer.append(",");
                }
            }
            lastWindowValue.computedColumnResult.put(TraceUtil.TRACE_ID_FLAG, buffer.toString());
        }
        return lastWindowValue;
    }

    public Object getAccumulatorByColumn(String column) {
        return aggColumnResult.getOrDefault(column, null);
    }

    public String getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(String groupBy) {
        this.groupBy = groupBy;
    }

    @Override
    public WindowValue clone() {
        WindowValue clonedValue = new WindowValue();
        clonedValue.setId(getId());
        clonedValue.setGmtModified(gmtModified);
        clonedValue.setGmtCreate(gmtCreate);
        clonedValue.setEndTime(endTime);
        clonedValue.setStartTime(startTime);
        clonedValue.setFireTime(fireTime);
        clonedValue.setMsgKey(msgKey);
        clonedValue.setAggColumnMap(aggColumnResult);
        clonedValue.setMaxOffset(getMaxOffset());
        clonedValue.setWindowInstanceId(windowInstanceId);
        clonedValue.setPartition(partition);
        clonedValue.setPartitionNum(partitionNum);
        clonedValue.setGroupBy(groupBy);
        clonedValue.setAggColumnResult(getAggColumnResult());
        clonedValue.setComputedColumnResult(getComputedColumnResult());
        clonedValue.setUpdateVersion(getUpdateVersion());

        return clonedValue;
    }

    public Long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(Long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    protected String encodeSQLContent(String content) {
        try {
            return Base64Utils.encode(content.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("encode sql content error " + content, e);
        }
    }

    protected String decodeSQLContent(String sqlContent) {
        try {
            if (StringUtil.isEmpty(sqlContent)) {
                return null;
            }
            return new String(Base64Utils.decode(sqlContent), "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("decode sql content error " + sqlContent, e);
        }
    }

}

