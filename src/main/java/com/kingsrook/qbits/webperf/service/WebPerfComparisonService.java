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

package com.kingsrook.qbits.webperf.service;


import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qbits.webperf.store.WebPerfStore;


/*******************************************************************************
 ** Reproducible before/after lab medians, with counts and actual sample windows.
 ** Three successful samples on each side and one Lighthouse major are required.
 *******************************************************************************/
public class WebPerfComparisonService
{
   private final WebPerfStore store;



   /*******************************************************************************
    ** Comparisons for one installation.
    *******************************************************************************/
   public WebPerfComparisonService(WebPerfStore store)
   {
      this.store = store;
   }



   /*******************************************************************************
    ** Recompute the run's attributed change after its results have been stored.
    *******************************************************************************/
   public void compareForRun(Map<String, Object> run) throws SQLException
   {
      if(run.get("change_event_id") != null)
      {
         List<Map<String, Object>> events = store.query("SELECT * FROM {change_event} WHERE id = ?", run.get("change_event_id"));
         if(!events.isEmpty())
         {
            compareTarget(events.get(0), WebPerfStore.asLong(run.get("target_id")), (String) run.get("strategy"));
         }
      }
   }



   /*******************************************************************************
    ** Record action: page through enabled targets and compare existing evidence.
    ** Sparse history stays absent instead of being filled with estimated values.
    *******************************************************************************/
   public int compare(long eventId) throws SQLException
   {
      return (store.transaction(tx ->
      {
         Map<String, Object> event = tx.query("SELECT * FROM {change_event} WHERE id = ? FOR UPDATE", eventId).get(0);
         WebPerfComparisonService service = new WebPerfComparisonService(tx);
         int count = 0;
         long after = 0;
         while(true)
         {
            List<Map<String, Object>> pages = tx.query("SELECT id FROM {target} WHERE enabled = TRUE AND site_key = ? AND id > ? ORDER BY id FETCH FIRST 100 ROWS ONLY",
               event.get("site_key"), after);
            if(pages.isEmpty())
            {
               return (count);
            }
            for(Map<String, Object> page : pages)
            {
               after = WebPerfStore.asLong(page.get("id"));
               for(String strategy : store.getConfig().getStrategies())
               {
                  count += service.compareTarget(event, after, strategy);
               }
            }
         }
      }));
   }



   /*******************************************************************************
    ** Serialize comparison upserts on the change row, shared by all callers.
    *******************************************************************************/
   private int compareTarget(Map<String, Object> event, long targetId, String strategy) throws SQLException
   {
      store.query("SELECT id FROM {change_event} WHERE id = ? FOR UPDATE", event.get("id"));
      Instant at = (Instant) event.get("observed_at");
      List<Map<String, Object>> after = store.query("SELECT * FROM {lab_run} WHERE target_id = ? AND strategy = ? AND status = 'SUCCEEDED'"
         + " AND started_at >= ? AND change_event_id = ? ORDER BY completed_at DESC, id DESC FETCH FIRST 3 ROWS ONLY", targetId, strategy, at, event.get("id"));
      if(after.size() != 3)
      {
         return (0);
      }
      String major = WebPerfEvaluationService.major(after.get(0));
      if(after.stream().anyMatch(row -> !major.equals(WebPerfEvaluationService.major(row))))
      {
         return (0);
      }
      List<Map<String, Object>> before = store.query("SELECT * FROM {lab_run} WHERE target_id = ? AND strategy = ? AND status = 'SUCCEEDED'"
         + " AND completed_at < ? AND completed_at >= ? AND (lighthouse_version = ? OR lighthouse_version LIKE ?)"
         + " ORDER BY completed_at DESC, id DESC FETCH FIRST 3 ROWS ONLY", targetId, strategy, at, at.minus(Duration.ofDays(30)), major, major + ".%");
      if(before.size() != 3)
      {
         return (0);
      }
      int count = 0;
      for(String metric : List.of("PERFORMANCE_SCORE", "LCP", "FCP", "TTFB"))
      {
         String column = WebPerfEvaluationService.LAB_METRICS.get(metric);
         if(before.stream().anyMatch(row -> row.get(column) == null) || after.stream().anyMatch(row -> row.get(column) == null))
         {
            continue;
         }
         double beforeValue = WebPerfEvaluationService.median(before, column);
         double afterValue = WebPerfEvaluationService.median(after, column);
         double delta = afterValue - beforeValue;
         Double percentage = beforeValue == 0 ? null : delta * 100 / beforeValue;
         if(percentage != null && Math.abs(percentage) >= 1000000)
         {
            percentage = null;
         }
         List<Map<String, Object>> existing = store.query("SELECT id FROM {comparison} WHERE change_event_id = ? AND target_id = ? AND source = 'LAB'"
            + " AND strategy = ? AND metric = ? AND segment = 'ALL'", event.get("id"), targetId, strategy, metric);
         if(existing.isEmpty())
         {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("change_event_id", event.get("id"));
            row.put("target_id", targetId);
            row.put("scope", "TARGET");
            row.put("source", "LAB");
            row.put("strategy", strategy);
            row.put("metric", metric);
            row.put("segment", "ALL");
            row.put("before_value", beforeValue);
            row.put("before_n", 3);
            row.put("before_window", window(before));
            row.put("after_value", afterValue);
            row.put("after_n", 3);
            row.put("after_window", window(after));
            row.put("delta", delta);
            row.put("delta_pct", percentage);
            store.insert(WebPerfSchema.COMPARISON, row);
         }
         else
         {
            store.update("UPDATE {comparison} SET before_value = ?, before_n = 3, before_window = ?, after_value = ?, after_n = 3, after_window = ?,"
               + " delta = ?, delta_pct = ?, modify_date = ? WHERE id = ?", beforeValue, window(before), afterValue, window(after), delta, percentage,
               Instant.now(), existing.get(0).get("id"));
         }
         count++;
      }
      return (count);
   }



   /*******************************************************************************
    ** The actual sample times, not an implied continuous measurement window.
    *******************************************************************************/
   private static String window(List<Map<String, Object>> samples)
   {
      return (samples.get(samples.size() - 1).get("completed_at") + " / " + samples.get(0).get("completed_at"));
   }
}
