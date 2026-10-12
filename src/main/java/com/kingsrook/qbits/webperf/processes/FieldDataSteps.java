/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.webperf.processes;


import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.field.CruxClient;
import com.kingsrook.qbits.webperf.service.WebPerfService;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractExtractStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;


/*******************************************************************************
 ** "Refresh Google field data" on Google Field Data: query the Chrome UX
 ** Report for the site's origin and every enabled tested page, on each form
 ** factor. Review says how many queries that is; Result says how many had
 ** data and how many Google has none for (NO_DATA, stored as such).
 *******************************************************************************/
public final class FieldDataSteps
{
   static final String ORIGIN = "webPerformanceOrigin";
   static final String URLS   = "webPerformanceUrls";



   /*******************************************************************************
    ** Steps only.
    *******************************************************************************/
   private FieldDataSteps()
   {
   }



   /*******************************************************************************
    ** The keys of a request record: ORIGIN and URL lists.
    *******************************************************************************/
   static Map<String, List<String>> keys(QRecord record)
   {
      Map<String, List<String>> keys = new LinkedHashMap<>();
      keys.put("ORIGIN", List.of(record.getValueString(ORIGIN)));
      String urls = record.getValueString(URLS);
      keys.put("URL", urls == null || urls.isEmpty() ? List.of() : Arrays.asList(urls.split("\n")));
      return (keys);
   }



   /*******************************************************************************
    ** Extract: one record naming the origin and the enabled pages.
    *******************************************************************************/
   public static class Extract extends AbstractExtractStep
   {
      /*******************************************************************************
       ** Build the request.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfService service = WebPerfRuntime.forInput(input).service();
         List<String>   urls    = new ArrayList<>();
         try
         {
            service.enabledTargets(false).forEach(row -> urls.add((String) row.get("url")));
         }
         catch(java.sql.SQLException e)
         {
            throw (new QException("Could not read the tested pages", e));
         }
         getRecordPipe().addRecord(new QRecord().withValue("id", 1).withValue("scopeKey", service.origin()).withValue(ORIGIN, service.origin())
            .withValue(URLS, String.join("\n", urls)));
      }
   }



   /*******************************************************************************
    ** Review: count the queries. Never calls Google.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine queries = Lines.line(Status.OK, "Google field data query will be made (origin and pages, each form factor)",
         "Google field data queries will be made (origin and pages, each form factor)", "query was made", "queries were made");
      private ProcessSummaryLine noKey;



      /*******************************************************************************
       ** Count one page (one record).
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         if(WebPerfRuntime.forInput(input).cruxClient() == null)
         {
            noKey = Lines.message(Status.ERROR, "No Google API key is configured, so Google field data cannot be checked");
            return;
         }
         for(QRecord record : input.getRecords())
         {
            int keys = keys(record).values().stream().mapToInt(List::size).sum();
            queries.incrementCount(keys * CruxClient.FORM_FACTORS.size());
            output.addRecord(record);
         }
      }



      /*******************************************************************************
       ** Review lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         Lines.addAll(lines, queries);
         if(noKey != null)
         {
            lines.add(noKey);
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** Execute: query and store.
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine withData = Lines.line(Status.OK, "result will have data", "results will have data",
         "query returned Google field data", "queries returned Google field data");
      private final ProcessSummaryLine noData   = Lines.line(Status.INFO, "result will have no data", "results will have no data",
         "query found no Google field data (not enough Chrome traffic; recorded as NO_DATA)",
         "queries found no Google field data (not enough Chrome traffic; recorded as NO_DATA)");



      /*******************************************************************************
       ** Query and store.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfService service = WebPerfRuntime.forInput(input).service();
         for(QRecord record : input.getRecords())
         {
            try
            {
               Map<String, Integer> counts = service.refreshFieldData(keys(record), Instant.now());
               withData.incrementCount(counts.getOrDefault("OK", 0));
               noData.incrementCount(counts.getOrDefault("NO_DATA", 0));
            }
            catch(Exception e)
            {
               throw (new QException("Google field data could not be refreshed: " + e.getMessage(), e));
            }
         }
      }



      /*******************************************************************************
       ** Result lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         Lines.addAll(lines, withData, noData);
         return (lines);
      }
   }
}
