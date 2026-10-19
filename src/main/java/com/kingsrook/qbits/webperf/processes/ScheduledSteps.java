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
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.service.WebPerfChangeService;
import com.kingsrook.qbits.webperf.service.WebPerfService;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The hidden scheduled processes' steps (design §6.2, D6): the queue worker,
 ** the daily KEY-page tests, the weekly full audit, the daily sitemap sync and
 ** the daily Google field data refresh.
 *******************************************************************************/
public final class ScheduledSteps
{
   private static final QLogger LOG = QLogger.getLogger(ScheduledSteps.class);



   /*******************************************************************************
    ** Steps only.
    *******************************************************************************/
   private ScheduledSteps()
   {
   }



   /*******************************************************************************
    ** Poll deploy probes and external change sources on the existing scheduler.
    *******************************************************************************/
   public static class ProbeChanges implements BackendStep
   {
      /*******************************************************************************
       ** Store changes and queue the affected pages.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         try
         {
            new WebPerfChangeService(WebPerfRuntime.forInput(input).store()).poll(Instant.now());
         }
         catch(Exception e)
         {
            throw (new QException("Checking site changes failed", e));
         }
      }
   }



   /*******************************************************************************
    ** Every minute: run due queued tests.
    *******************************************************************************/
   public static class RunQueued implements BackendStep
   {
      /*******************************************************************************
       ** Run.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         try
         {
            WebPerfRuntime.forInput(input).service().runDue(Instant.now());
         }
         catch(Exception e)
         {
            throw (new QException("Running queued page speed tests failed", e));
         }
      }
   }



   /*******************************************************************************
    ** Daily: queue the KEY pages on every strategy.
    *******************************************************************************/
   public static class KeyTests implements BackendStep
   {
      /*******************************************************************************
       ** Run.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         queue(input, true, "daily key pages");
      }
   }



   /*******************************************************************************
    ** Weekly: queue every enabled page on every strategy.
    *******************************************************************************/
   public static class SiteAudit implements BackendStep
   {
      /*******************************************************************************
       ** Run.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         queue(input, false, "weekly site audit");
      }
   }



   /*******************************************************************************
    ** Daily: sync tested pages from the URL source (when one is configured).
    *******************************************************************************/
   public static class Sync implements BackendStep
   {
      /*******************************************************************************
       ** Run.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfRuntime runtime = WebPerfRuntime.forInput(input);
         if(runtime.getConfig().getUrlSource() == null)
         {
            return;
         }
         try
         {
            WebPerfService          service = runtime.service();
            WebPerfService.SyncPlan plan    = service.planSync(runtime.getConfig().getUrlSource().urls());
            service.applySync(plan, Instant.now());
            LOG.info("Web performance sitemap sync", logPair("added", plan.newUrls().size()), logPair("removed", plan.removedIds().size()));
         }
         catch(Exception e)
         {
            throw (new QException("Sitemap sync failed: " + e.getMessage(), e));
         }
      }
   }



   /*******************************************************************************
    ** Daily: refresh Google field data (with a key).
    *******************************************************************************/
   public static class FieldData implements BackendStep
   {
      /*******************************************************************************
       ** Run.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         WebPerfRuntime runtime = WebPerfRuntime.forInput(input);
         if(runtime.cruxClient() == null)
         {
            return;
         }
         try
         {
            WebPerfService service = runtime.service();
            List<String>   urls    = service.enabledTargets(false).stream().map(row -> (String) row.get("url")).toList();
            Map<String, Integer> counts = service.refreshFieldData(Map.of("ORIGIN", List.of(service.origin()), "URL", urls), Instant.now());
            LOG.info("Web performance field data refreshed", logPair("counts", counts));
         }
         catch(Exception e)
         {
            throw (new QException("Google field data refresh failed: " + e.getMessage(), e));
         }
      }
   }



   /*******************************************************************************
    ** Queue enabled pages (KEY only or all) on every strategy.
    *******************************************************************************/
   static void queue(RunBackendStepInput input, boolean keyOnly, String reason) throws QException
   {
      try
      {
         WebPerfService           service = WebPerfRuntime.forInput(input).service();
         Instant                  now     = Instant.now();
         WebPerfService.QueuePlan plan    = service.enqueue(service.scheduledRequests(keyOnly, reason, now), now);
         LOG.info("Web performance tests queued", logPair("reason", reason), logPair("queued", plan.queue().size()), logPair("merged", plan.merged().size()),
            logPair("overCap", plan.overQuota().size()));
      }
      catch(java.sql.SQLException e)
      {
         throw (new QException("Queueing " + reason + " failed", e));
      }
   }
}
