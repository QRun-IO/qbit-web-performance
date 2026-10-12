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


import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.service.WebPerfService;
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


/*******************************************************************************
 ** "Retry" on lab runs: runs that failed, hit a Lighthouse runtime error or
 ** were skipped by the run cap are queued again as new runs. The original
 ** rows are kept unchanged, so the history shows what happened.
 *******************************************************************************/
public final class RetryRunsSteps
{
   /** Statuses that can be retried. */
   public static final Set<String> RETRYABLE = Set.of("FAILED", "RUNTIME_ERROR", "SKIPPED_QUOTA");



   /*******************************************************************************
    ** Steps only.
    *******************************************************************************/
   private RetryRunsSteps()
   {
   }



   /*******************************************************************************
    ** Review: which runs can be retried.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine retry = Lines.line(Status.OK, "run will be queued again", "runs will be queued again",
         "run was queued again", "runs were queued again");
      private final ProcessSummaryLine skip  = Lines.line(Status.INFO, "run did not fail and will not be retried",
         "runs did not fail and will not be retried", "run had not failed (not retried)", "runs had not failed (not retried)");



      /*******************************************************************************
       ** Sort one page of runs.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            if(RETRYABLE.contains(record.getValueString("status")) && record.getValue("targetId") != null)
            {
               retry.incrementCountAndAddPrimaryKey(record.getValue("id"));
               output.addRecord(record);
            }
            else
            {
               skip.incrementCountAndAddPrimaryKey(record.getValue("id"));
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
         Lines.addAll(lines, retry, skip);
         return (lines);
      }



      /*******************************************************************************
       ** The not-retried line, repeated on the Result screen.
       *******************************************************************************/
      ProcessSummaryLine skipLine()
      {
         return (skip);
      }
   }



   /*******************************************************************************
    ** Execute: queue new runs for the retryable ones.
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine queued = Lines.line(Status.OK, "run will be queued", "runs will be queued", "run was queued again",
         "runs were queued again");
      private final ProcessSummaryLine merged = Lines.line(Status.INFO, "run will merge", "runs will merge",
         "run was already waiting for that page (merged)", "runs were already waiting for those pages (merged)");
      private final ProcessSummaryLine capped = Lines.line(Status.WARNING, "run will be skipped", "runs will be skipped",
         "run was over today's run cap (recorded as skipped)", "runs were over today's run cap (recorded as skipped)");



      /*******************************************************************************
       ** Queue one page.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfService                     service  = WebPerfRuntime.forInput(input).service();
         List<WebPerfService.RunRequest>    requests = new ArrayList<>();
         String                             batch    = java.util.UUID.randomUUID().toString();
         for(QRecord record : input.getRecords())
         {
            requests.add(new WebPerfService.RunRequest(record.getValueLong("targetId"), record.getValueString("url"), record.getValueString("strategy"),
               "MANUAL", "retry of run " + record.getValue("id"), batch, Instant.now()));
         }
         try
         {
            WebPerfService.QueuePlan plan = service.enqueue(requests, Instant.now());
            queued.incrementCount(plan.queue().size());
            merged.incrementCount(plan.merged().size());
            capped.incrementCount(plan.overQuota().size());
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
         Lines.addAll(lines, queued, merged, capped);
         if(getTransformStep() instanceof Transform transform)
         {
            Lines.addAll(lines, transform.skipLine());
         }
         return (lines);
      }
   }
}
