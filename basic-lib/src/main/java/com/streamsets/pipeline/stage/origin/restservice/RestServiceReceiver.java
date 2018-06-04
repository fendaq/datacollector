/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.restservice;

import com.google.common.annotations.VisibleForTesting;
import com.streamsets.pipeline.api.BatchContext;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.lib.generator.DataGenerator;
import com.streamsets.pipeline.lib.generator.DataGeneratorException;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.lib.http.HttpConfigs;
import com.streamsets.pipeline.stage.origin.httpserver.PushHttpReceiver;
import com.streamsets.pipeline.stage.origin.lib.DataParserFormatConfig;
import com.streamsets.pipeline.stage.util.http.HttpStageUtil;
import org.apache.commons.collections.CollectionUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RestServiceReceiver extends PushHttpReceiver {

  final static String EMPTY_PAYLOAD_RECORD_HEADER_ATTR_NAME = "emptyPayloadRecord";
  private final RestServiceResponseConfigBean responseConfig;
  private DataGeneratorFactory dataGeneratorFactory;

  RestServiceReceiver(
      HttpConfigs httpConfigs,
      int maxRequestSizeMB,
      DataParserFormatConfig dataParserFormatConfig,
      RestServiceResponseConfigBean responseConfig
  ) {
    super(httpConfigs, maxRequestSizeMB, dataParserFormatConfig);
    this.responseConfig = responseConfig;
  }

  @Override
  public List<Stage.ConfigIssue> init(Stage.Context context) {
    dataGeneratorFactory = responseConfig.dataGeneratorFormatConfig.getDataGeneratorFactory();
    return super.init(context);
  }

  @VisibleForTesting
  private DataGeneratorFactory getGeneratorFactory() {
    return dataGeneratorFactory;
  }

  @Override
  public boolean process(HttpServletRequest req, InputStream is, HttpServletResponse resp) throws IOException {
    // Capping the size of the request based on configuration to avoid OOME
    is = createBoundInputStream(is);

    // Create new batch (we create it up front for metrics gathering purposes
    BatchContext batchContext = getContext().startBatch();

    List<Record> requestRecords = parseRequestPayload(req, is);

    // If HTTP Request Payload is empty, add Empty Payload Record with all HTTP Request Attributes
    if (CollectionUtils.isEmpty(requestRecords)) {
      requestRecords.add(createEmptyPayloadRecord(req));
    }

    // dispatch records to batch
    for (Record record : requestRecords) {
      batchContext.getBatchMaker().addRecord(record);
    }

    boolean returnValue = getContext().processBatch(batchContext);

    // Send response
    List<Record> responseRecords = batchContext.getSourceResponseRecords();
    if (CollectionUtils.isNotEmpty(responseRecords)) {
      resp.setContentType(HttpStageUtil.getContentType(responseConfig.dataFormat));
      try (DataGenerator dataGenerator = getGeneratorFactory().getGenerator(resp.getOutputStream())) {
        for (Record responseRecord : batchContext.getSourceResponseRecords()) {
          dataGenerator.write(responseRecord);
        }
        dataGenerator.flush();
      } catch (DataGeneratorException e) {
        throw new IOException(e);
      }
    }

    return returnValue;
  }

  private Record createEmptyPayloadRecord(HttpServletRequest req) {
    Map<String, String> customHeaderAttributes = getCustomHeaderAttributes(req);
    Record placeholderRecord = getContext().createRecord("emptyPayload");
    placeholderRecord.set(Field.createListMap(new LinkedHashMap<>()));
    customHeaderAttributes.forEach((key, value) -> placeholderRecord.getHeader().setAttribute(key, value));
    placeholderRecord.getHeader().setAttribute(EMPTY_PAYLOAD_RECORD_HEADER_ATTR_NAME, "true");
    return placeholderRecord;
  }

}
