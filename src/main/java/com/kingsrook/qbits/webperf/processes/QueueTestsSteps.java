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


import java.io.Serializable;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.webperf.WebPerfQBitConfig;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qbits.webperf.service.WebPerfService;
import com.kingsrook.qbits.webperf.store.WebPerfStore;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** "Test page speed" and "Run site audit": queue lab runs for the selected
 ** tested pages, or for the pages of the selected content records, on every
 ** configured strategy. Review counts what will be queued, merged into a run
 ** that is already waiting, or held back by today's run cap; nothing is
 ** written until Execute. Results arrive as the queue worker runs the tests.
 *******************************************************************************/
public final class QueueTestsSteps
{
   static final String TARGET_ID = "webperfTargetId";
   static final String URL       = "webperfUrl";



   /*******************************************************************************
    ** Steps only.
    *******************************************************************************/
   private QueueTestsSteps()
   {
   }



   /*******************************************************************************
    ** The tested pages of a page of input records: the records themselves on
    ** the tested-pages table, else the pages linked to the content records.
    **
    ** @param runtime     the runtime
    ** @param sourceTable the process's source table
    ** @param records     the records
    ** @return record id to its pages (id, url, enabled)
    ** @throws SQLException on failure
    *******************************************************************************/
   static Map<Serializable, List<Map<String, Object>>> pagesOf(WebPerfRuntime runtime, String sourceTable, List<QRecord> records) throws SQLException
   {
      WebPerfQBitConfig                             config = runtime.getConfig();
      WebPerfStore                                  store  = runtime.store();
      Map<Serializable, List<Map<String, Object>>> pages  = new LinkedHashMap<>();
      boolean                                       direct = config.applyPrefix(WebPerfSchema.TARGET).equals(sourceTable);
      for(QRecord record : records)
      {
         Serializable id = record.getValue("id");
         if(direct)
         {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", record.getValue("id"));
            page.put("url", record.getValueString("url"));
            page.put("enabled", record.getValueBoolean("enabled"));
            pages.put(id, List.of(page));
         }
         else
         {
            pages.put(id, store.query("SELECT id, url, enabled FROM {target} WHERE content_table = ? AND content_id = ? ORDER BY id", sourceTable,
               Long.valueOf(String.valueOf(id))));
         }
      }
      return (pages);
   }



   /*******************************************************************************
    ** Review: count what will be queued. Never writes.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine pages     = Lines.line(Status.OK, "page will be tested", "pages will be tested",
         "page was tested", "pages were tested");
      private final ProcessSummaryLine runs      = Lines.line(Status.OK, "run will be queued (mobile and desktop are separate runs)",
         "runs will be queued (mobile and desktop are separate runs)", "run was queued", "runs were queued");
      private final ProcessSummaryLine merged    = Lines.line(Status.INFO, "run is already waiting for that page (merged, not queued twice)",
         "runs are already waiting for those pages (merged, not queued twice)", "run was already waiting (merged)", "runs were already waiting (merged)");
      private final ProcessSummaryLine overQuota = Lines.line(Status.WARNING, "run is over today's run cap and will be recorded as skipped (use Retry tomorrow)",
         "runs are over today's run cap and will be recorded as skipped (use Retry tomorrow)", "run was over today's run cap (skipped)",
         "runs were over today's run cap (skipped)");
      private final ProcessSummaryLine noPage    = Lines.line(Status.INFO, "record has no tested page (not in the sitemap, for example a draft)",
         "records have no tested page (not in the sitemap, for example drafts)", "record had no tested page", "records had no tested page");
      private final ProcessSummaryLine disabled  = Lines.line(Status.INFO, "page is disabled and will be skipped", "pages are disabled and will be skipped",
         "page was disabled (skipped)", "pages were disabled (skipped)");

      private ProcessSummaryLine quotaNote;
      private ProcessSummaryLine noKey;
      private Set<String>        open;
      private int                counted;
      private int                cap;



      /*******************************************************************************
       ** Load the open runs and today's count once.
       *******************************************************************************/
      @Override
      public void preRun(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfRuntime runtime = WebPerfRuntime.forInput(input);
         WebPerfService service = runtime.service();
         try
         {
            WebPerfService.QueuePlan none = service.planQueue(List.of(), Instant.now());
            counted = none.runsToday();
            cap = runtime.getConfig().getMaxRunsPerDay();
            open = new HashSet<>();
            for(Map<String, Object> row : runtime.store().query("SELECT target_id, strategy FROM {lab_run} WHERE status IN ('QUEUED', 'RUNNING')"))
            {
               open.add(row.get("target_id") + "|" + row.get("strategy"));
            }
         }
         catch(SQLException e)
         {
            throw (new QException("Could not read the test queue", e));
         }
         quotaNote = Lines.message(Status.INFO, "Runs today before this: " + counted + " of " + cap + " (Google allows " + WebPerfQBitConfig.PSI_DAILY_QUOTA + ")");
         if(runtime.labRunner() == null)
         {
            noKey = Lines.message(Status.WARNING, "No Google API key is configured, so the runs will wait in the queue until one is");
         }
      }



      /*******************************************************************************
       ** Count one page of records.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfRuntime runtime    = WebPerfRuntime.forInput(input);
         List<String>   strategies = runtime.getConfig().getStrategies();
         Map<Serializable, List<Map<String, Object>>> pagesById;
         try
         {
            pagesById = pagesOf(runtime, input.getValueString(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE), input.getRecords());
         }
         catch(SQLException e)
         {
            throw (new QException("Could not read the tested pages", e));
         }

         for(Map.Entry<Serializable, List<Map<String, Object>>> entry : pagesById.entrySet())
         {
            if(entry.getValue().isEmpty())
            {
               noPage.incrementCountAndAddPrimaryKey(entry.getKey());
               continue;
            }
            for(Map<String, Object> page : entry.getValue())
            {
               if(Boolean.FALSE.equals(page.get("enabled")))
               {
                  disabled.incrementCountAndAddPrimaryKey(entry.getKey());
                  continue;
               }
               boolean any = false;
               for(String strategy : strategies)
               {
                  String key = page.get("id") + "|" + strategy;
                  if(!open.add(key))
                  {
                     merged.incrementCount();
                  }
                  else if(counted >= cap)
                  {
                     overQuota.incrementCount();
                     any = true;
                  }
                  else
                  {
                     counted++;
                     runs.incrementCount();
                     any = true;
                  }
               }
               if(any)
               {
                  pages.incrementCountAndAddPrimaryKey(entry.getKey());
                  output.addRecord(new QRecord().withValue(TARGET_ID, WebPerfStore.asLong(page.get("id"))).withValue(URL, (String) page.get("url")));
               }
            }
         }
      }



      /*******************************************************************************
       ** Review lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         Lines.addAll(lines, pages, runs, merged, overQuota, noPage, disabled);
         if(quotaNote != null)
         {
            lines.add(quotaNote);
         }
         if(noKey != null)
         {
            lines.add(noKey);
         }
         return (lines);
      }



      /*******************************************************************************
       ** Lines the Result screen repeats.
       *******************************************************************************/
      List<ProcessSummaryLine> skippedLines()
      {
         return (List.of(merged, noPage, disabled));
      }



      /*******************************************************************************
       ** The merged line (Execute adds runs merged at write time).
       *******************************************************************************/
      ProcessSummaryLine mergedLine()
      {
         return (merged);
      }
   }



   /*******************************************************************************
    ** Execute: queue the runs (merging and capping again at write time).
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine queued    = Lines.line(Status.OK, "run will be queued", "runs will be queued", "run was queued",
         "runs were queued; results appear in Lab Runs as the tests finish (usually within minutes)");
      private final ProcessSummaryLine overQuota = Lines.line(Status.WARNING, "run will be skipped", "runs will be skipped",
         "run was over today's run cap (recorded as skipped; use Retry)", "runs were over today's run cap (recorded as skipped; use Retry)");



      /*******************************************************************************
       ** Queue one page of pages.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfRuntime            runtime = WebPerfRuntime.forInput(input);
         WebPerfService            service = runtime.service();
         List<Map<String, Object>> targets = new ArrayList<>();
         for(QRecord record : input.getRecords())
         {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("id", record.getValue(TARGET_ID));
            target.put("url", record.getValueString(URL));
            targets.add(target);
         }
         String user = QContext.getQSession() == null || QContext.getQSession().getUser() == null ? null : QContext.getQSession().getUser().getIdReference();
         String trigger = input.getValueString(WebPerfProcesses.TRIGGER_FIELD);
         try
         {
            WebPerfService.QueuePlan plan = service.enqueue(service.requestsFor(targets, trigger == null ? "MANUAL" : trigger,
               input.getProcessName() + (user == null ? "" : " by " + user), Instant.now()), Instant.now());
            queued.incrementCount(plan.queue().size());
            if(!plan.merged().isEmpty() && getTransformStep() instanceof Transform transform)
            {
               transform.mergedLine().incrementCount(plan.merged().size());
            }
            overQuota.incrementCount(plan.overQuota().size());
         }
         catch(SQLException e)
         {
            throw (new QException("Could not queue the runs", e));
         }
      }



      /*******************************************************************************
       ** Result lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         Lines.addAll(lines, queued, overQuota);
         if(getTransformStep() instanceof Transform transform)
         {
            Lines.addAll(lines, transform.skippedLines().toArray(new ProcessSummaryLine[0]));
         }
         return (lines);
      }
   }
}
