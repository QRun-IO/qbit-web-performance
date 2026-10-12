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
import java.util.List;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.service.WebPerfService;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QUserFacingException;
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
 ** "Sync pages from sitemap" on Tested Pages: the URL source (the sitemap by
 ** default) is read once and compared with the tested pages. Review counts
 ** pages to add, pages still listed, and pages no longer listed (kept, with
 ** their history, and marked "not in sitemap"). Nothing is ever deleted.
 *******************************************************************************/
public final class SyncPagesSteps
{
   static final String URLS = "webperfUrls";



   /*******************************************************************************
    ** Steps only.
    *******************************************************************************/
   private SyncPagesSteps()
   {
   }



   /*******************************************************************************
    ** The URLs of a sync record.
    *******************************************************************************/
   static List<String> urls(QRecord record)
   {
      String joined = record.getValueString(URLS);
      return (joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split("\n")));
   }



   /*******************************************************************************
    ** Extract: one record holding every listed URL.
    *******************************************************************************/
   public static class Extract extends AbstractExtractStep
   {
      /*******************************************************************************
       ** Read the URL source.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfRuntime runtime = WebPerfRuntime.forInput(input);
         if(runtime.getConfig().getUrlSource() == null)
         {
            throw (new QUserFacingException("No URL source (sitemap) is configured for web performance"));
         }
         List<String> urls;
         try
         {
            urls = runtime.getConfig().getUrlSource().urls();
         }
         catch(Exception e)
         {
            throw (new QUserFacingException("The sitemap could not be read: " + e.getMessage()));
         }
         getRecordPipe().addRecord(new QRecord().withValue("id", 1).withValue("source", "Sitemap").withValue("urlCount", urls.size())
            .withValue(URLS, String.join("\n", urls)));
      }
   }



   /*******************************************************************************
    ** Review: compare with the tested pages. Never writes.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine added   = Lines.line(Status.OK, "new page will be added", "new pages will be added",
         "new page was added", "new pages were added");
      private final ProcessSummaryLine seen    = Lines.line(Status.INFO, "page is still in the sitemap (no change)", "pages are still in the sitemap (no change)",
         "page was still in the sitemap", "pages were still in the sitemap");
      private final ProcessSummaryLine removed = Lines.line(Status.WARNING, "page is no longer in the sitemap and will be marked so (kept, with its history)",
         "pages are no longer in the sitemap and will be marked so (kept, with their history)", "page was marked not in the sitemap (kept)",
         "pages were marked not in the sitemap (kept)");



      /*******************************************************************************
       ** Plan the sync.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfService service = WebPerfRuntime.forInput(input).service();
         for(QRecord record : input.getRecords())
         {
            try
            {
               WebPerfService.SyncPlan plan = service.planSync(urls(record));
               added.incrementCount(plan.newUrls().size());
               seen.incrementCount(plan.seenIds().size());
               removed.incrementCount(plan.removedIds().size());
               plan.removedIds().forEach(id -> addKey(removed, id));
            }
            catch(java.sql.SQLException e)
            {
               throw (new QException("Could not read the tested pages", e));
            }
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
         Lines.addAll(lines, added, seen, removed);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Execute: apply the sync (planned again, so it is current).
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine added   = Lines.line(Status.OK, "page will be added", "pages will be added", "new page was added",
         "new pages were added");
      private final ProcessSummaryLine seen    = Lines.line(Status.INFO, "page will be kept", "pages will be kept", "page was still in the sitemap",
         "pages were still in the sitemap");
      private final ProcessSummaryLine removed = Lines.line(Status.WARNING, "page will be marked", "pages will be marked",
         "page was marked not in the sitemap (kept)", "pages were marked not in the sitemap (kept)");



      /*******************************************************************************
       ** Apply.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfService service = WebPerfRuntime.forInput(input).service();
         for(QRecord record : input.getRecords())
         {
            try
            {
               WebPerfService.SyncPlan plan = service.planSync(urls(record));
               service.applySync(plan, Instant.now());
               added.incrementCount(plan.newUrls().size());
               seen.incrementCount(plan.seenIds().size());
               removed.incrementCount(plan.removedIds().size());
               plan.removedIds().forEach(id -> addKey(removed, id));
            }
            catch(java.sql.SQLException e)
            {
               throw (new QException("Could not update the tested pages", e));
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
         Lines.addAll(lines, added, seen, removed);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Link a page to a line (the count is kept separately).
    *******************************************************************************/
   static void addKey(ProcessSummaryLine line, Long id)
   {
      if(line.getPrimaryKeys() == null)
      {
         line.setPrimaryKeys(new ArrayList<>());
      }
      line.getPrimaryKeys().add(id);
   }
}
