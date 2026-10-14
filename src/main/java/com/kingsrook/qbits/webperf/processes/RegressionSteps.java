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
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.service.WebPerfComparisonService;
import com.kingsrook.qbits.webperf.service.WebPerfEvaluationService;
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
 ** Standard streamed Review/Result actions for regression status and comparisons.
 *******************************************************************************/
public final class RegressionSteps
{
   public static final String NOTE = "resolutionNote";



   /*******************************************************************************
    ** Steps only.
    *******************************************************************************/
   private RegressionSteps()
   {
   }



   /*******************************************************************************
    ** Review open records; closed records are explicitly skipped.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private ProcessSummaryLine change;
      private final ProcessSummaryLine skip = Lines.line(Status.INFO, "closed regression will be skipped", "closed regressions will be skipped",
         "closed regression was skipped", "closed regressions were skipped");



      /*******************************************************************************
       ** Validate the note and classify this page without writing anything.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String status = input.getValueString(WebPerfProcesses.TRIGGER_FIELD);
         String note = input.getValueString(NOTE);
         if(note == null || note.isBlank() || note.length() > 2000)
         {
            throw (new QException("Enter a resolution note of 1 to 2000 characters"));
         }
         if(change == null)
         {
            String verb = "DISMISSED".equals(status) ? "dismissed" : "marked resolved";
            change = Lines.line(Status.OK, "regression will be " + verb, "regressions will be " + verb,
               "regression was " + verb, "regressions were " + verb);
         }
         for(QRecord record : input.getRecords())
         {
            if(List.of("SUSPECTED", "CONFIRMED").contains(record.getValueString("status")))
            {
               change.incrementCountAndAddPrimaryKey(record.getValue("id"));
               output.addRecord(record);
            }
            else
            {
               skip.incrementCountAndAddPrimaryKey(record.getValue("id"));
            }
         }
      }



      /*******************************************************************************
       ** Review summary.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         if(change != null)
         {
            Lines.addAll(lines, change);
         }
         Lines.addAll(lines, skip);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Apply the requested state under a database lock, rechecking eligibility.
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private ProcessSummaryLine changed;
      private final ProcessSummaryLine skipped = Lines.line(Status.INFO, "regression will be skipped", "regressions will be skipped",
         "regression was already closed", "regressions were already closed");



      /*******************************************************************************
       ** Apply one page and report only the rows actually changed.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String status = input.getValueString(WebPerfProcesses.TRIGGER_FIELD);
         if(changed == null)
         {
            String verb = "DISMISSED".equals(status) ? "dismissed" : "marked resolved";
            changed = Lines.line(Status.OK, "regression will be " + verb, "regressions will be " + verb,
               "regression was " + verb, "regressions were " + verb);
         }
         WebPerfEvaluationService service = new WebPerfEvaluationService(WebPerfRuntime.forInput(input).store());
         try
         {
            for(QRecord record : input.getRecords())
            {
               boolean updated = service.finishRegression(record.getValueLong("id"), status, input.getValueString(NOTE), Instant.now());
               (updated ? changed : skipped).incrementCountAndAddPrimaryKey(record.getValue("id"));
            }
         }
         catch(SQLException | IllegalArgumentException e)
         {
            throw (new QException("Could not update the regressions", e));
         }
      }



      /*******************************************************************************
       ** Result summary.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         if(changed != null)
         {
            Lines.addAll(lines, changed);
         }
         Lines.addAll(lines, skipped);
         if(getTransformStep() instanceof Transform transform)
         {
            Lines.addAll(lines, transform.skip);
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** Review comparisons using recorded lab history.
    *******************************************************************************/
   public static class CompareTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine changes = Lines.line(Status.OK, "site change will be compared using recorded lab samples",
         "site changes will be compared using recorded lab samples", "site change was compared", "site changes were compared");



      /*******************************************************************************
       ** Pass through a bounded page of selected changes.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            changes.incrementCountAndAddPrimaryKey(record.getValue("id"));
            output.addRecord(record);
         }
      }



      /*******************************************************************************
       ** Review summary.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         Lines.addAll(lines, changes);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Write comparisons when both sides have three comparable measurements.
    *******************************************************************************/
   public static class CompareLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine compared = Lines.line(Status.OK, "comparison will be recorded", "comparisons will be recorded",
         "comparison was recorded", "comparisons were recorded");
      private final ProcessSummaryLine insufficient = Lines.line(Status.INFO, "change needs more samples", "changes need more samples",
         "change had fewer than three comparable samples on each side", "changes had fewer than three comparable samples on each side");



      /*******************************************************************************
       ** Compute and persist this page of selected changes.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         try
         {
            WebPerfComparisonService service = new WebPerfComparisonService(WebPerfRuntime.forInput(input).store());
            for(QRecord record : input.getRecords())
            {
               int count = service.compare(record.getValueLong("id"));
               compared.incrementCount(count);
               if(count == 0)
               {
                  insufficient.incrementCountAndAddPrimaryKey(record.getValue("id"));
               }
            }
         }
         catch(SQLException e)
         {
            throw (new QException("Could not compare the site changes", e));
         }
      }



      /*******************************************************************************
       ** Result summary, including an honest insufficient-history line.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         Lines.addAll(lines, compared, insufficient);
         return (lines);
      }
   }
}
