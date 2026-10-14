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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import com.kingsrook.qbits.webperf.WebPerfFeature;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qbits.webperf.spi.ChangeEventSource;
import com.kingsrook.qbits.webperf.store.WebPerfStore;
import com.kingsrook.qqq.backend.core.alerts.AlertRecordRef;
import com.kingsrook.qqq.backend.core.alerts.AlertSeverity;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.QAlerts;


/*******************************************************************************
 ** Budget breaches and confirmed lab regressions, backed by ordinary records.
 ** Target row locks serialize evaluation across workers. Baselines and samples
 ** are bounded, and evaluation progress commits with the resulting records.
 *******************************************************************************/
public class WebPerfEvaluationService
{
   public static final String ALERT_TYPE = "webperf.regression";
   public static final Map<String, String> LAB_METRICS = Map.of(
      "PERFORMANCE_SCORE", "score_performance", "ACCESSIBILITY_SCORE", "score_accessibility",
      "BEST_PRACTICES_SCORE", "score_best_practices", "SEO_SCORE", "score_seo",
      "LCP", "lcp_ms", "FCP", "fcp_ms", "CLS", "cls", "TBT", "tbt_ms", "TTFB", "ttfb_ms");
   private final WebPerfStore store;



   /*******************************************************************************
    ** Evaluation for one installation.
    *******************************************************************************/
   public WebPerfEvaluationService(WebPerfStore store)
   {
      this.store = store;
   }



   /*******************************************************************************
    ** Bounded work per worker tick, including results left by an interrupted tick.
    *******************************************************************************/
   public void evaluatePending() throws SQLException
   {
      for(Map<String, Object> run : store.query("SELECT id FROM {lab_run} WHERE status = 'SUCCEEDED' AND evaluated_at IS NULL ORDER BY completed_at, id FETCH FIRST 100 ROWS ONLY"))
      {
         evaluateLab(WebPerfStore.asLong(run.get("id")));
      }
      for(Map<String, Object> field : store.query("SELECT id FROM {field_snapshot} WHERE evaluated_at IS NULL ORDER BY id FETCH FIRST 100 ROWS ONLY"))
      {
         evaluateField(WebPerfStore.asLong(field.get("id")));
      }
   }



   /*******************************************************************************
    ** Evaluate a stored successful result once; publish alerts after commit.
    *******************************************************************************/
   public void evaluateLab(long runId) throws SQLException
   {
      List<Long> changed = store.transaction(tx ->
      {
         List<Map<String, Object>> runs = tx.query("SELECT * FROM {lab_run} WHERE id = ? AND status = 'SUCCEEDED'", runId);
         if(runs.isEmpty())
         {
            return (List.of());
         }
         Map<String, Object> run = runs.get(0);
         List<Map<String, Object>> targets = tx.query("SELECT * FROM {target} WHERE id = ? FOR UPDATE", run.get("target_id"));
         if(targets.isEmpty() || !tx.query("SELECT id FROM {lab_run} WHERE id = ? AND evaluated_at IS NOT NULL", runId).isEmpty())
         {
            return (List.of());
         }
         Map<String, Object> target = targets.get(0);
         WebPerfEvaluationService evaluator = new WebPerfEvaluationService(tx);
         List<Long> transitions = new ArrayList<>();
         evaluator.versionChange(run, transitions);
         List<Map<String, Object>> baseline = evaluator.baseline(run);
         for(Map.Entry<String, String> metric : LAB_METRICS.entrySet())
         {
            if(run.get(metric.getValue()) != null)
            {
               evaluator.evaluateMetric(target, run, metric.getKey(), metric.getValue(), baseline, transitions);
            }
         }
         evaluator.updateCount(target.get("id"));
         if(store.getConfig().isOn(WebPerfFeature.CDN_COMPARE))
         {
            new WebPerfComparisonService(tx).compareForRun(run);
         }
         tx.update("UPDATE {lab_run} SET evaluated_at = ? WHERE id = ?", Instant.now(), runId);
         return (transitions);
      });
      changed.forEach(this::sendAlert);
   }



   /*******************************************************************************
    ** CrUX URL budgets use real p75 values, never NO_DATA, errors or origin
    ** fallback as page measurements. A new collection period can resolve a breach.
    *******************************************************************************/
   public void evaluateField(long snapshotId) throws SQLException
   {
      List<Long> changed = store.transaction(tx ->
      {
         Map<String, Object> field = tx.query("SELECT * FROM {field_snapshot} WHERE id = ? FOR UPDATE", snapshotId).get(0);
         List<Long> transitions = new ArrayList<>();
         if(field.get("evaluated_at") != null)
         {
            return (transitions);
         }
         if("OK".equals(field.get("status")) && "URL".equals(field.get("scope")))
         {
            List<Map<String, Object>> targets = tx.query("SELECT * FROM {target} WHERE site_key = ? AND url = ? FOR UPDATE",
               new WebPerfChangeService(tx).siteKey(), field.get("scope_key"));
            WebPerfEvaluationService evaluator = new WebPerfEvaluationService(tx);
            for(Map<String, Object> target : targets)
            {
               for(String metric : List.of("LCP", "INP", "CLS", "FCP", "TTFB"))
               {
                  String column = "p75_" + metric.toLowerCase(Locale.ROOT) + ("CLS".equals(metric) ? "" : "_ms");
                  Double value = WebPerfStore.asDouble(field.get(column));
                  if(value == null)
                  {
                     continue;
                  }
                  String strategy = "PHONE".equals(field.get("form_factor")) ? "MOBILE" : (String) field.get("form_factor");
                  Map<String, Object> budget = evaluator.breachedBudget(target, "CRUX", strategy, metric, value);
                  List<Map<String, Object>> open = tx.query("SELECT * FROM {regression} WHERE target_id = ? AND source = 'CRUX' AND strategy = ? AND metric = ?"
                     + " AND status = 'CONFIRMED' FETCH FIRST 1 ROWS ONLY", target.get("id"), field.get("form_factor"), metric);
                  Instant at = (Instant) field.get("last_checked_at");
                  if(budget != null && open.isEmpty())
                  {
                     Map<String, Object> row = new LinkedHashMap<>();
                     row.put("target_id", target.get("id"));
                     row.put("content_table", target.get("content_table"));
                     row.put("content_id", target.get("content_id"));
                     row.put("source", "CRUX");
                     row.put("strategy", field.get("form_factor"));
                     row.put("metric", metric);
                     row.put("current_value", value);
                     row.put("current_ref", "field:" + snapshotId);
                     row.put("budget_id", budget.get("id"));
                     row.put("severity", budget.get("severity"));
                     row.put("status", "CONFIRMED");
                     row.put("detected_at", at);
                     row.put("confirmed_at", at);
                     transitions.add(tx.insert(WebPerfSchema.REGRESSION, row));
                  }
                  else if(!open.isEmpty())
                  {
                     Map<String, Object> regression = open.get(0);
                     tx.update("UPDATE {regression} SET current_value = ?, current_ref = ?, modify_date = ? WHERE id = ?",
                        value, "field:" + snapshotId, at, regression.get("id"));
                     if(budget == null)
                     {
                        evaluator.close(regression, "RESOLVED", "Google field data is within its budget", at);
                        transitions.add(WebPerfStore.asLong(regression.get("id")));
                     }
                  }
               }
               evaluator.updateCount(target.get("id"));
            }
         }
         tx.update("UPDATE {field_snapshot} SET evaluated_at = ? WHERE id = ?", Instant.now(), snapshotId);
         return (transitions);
      });
      changed.forEach(this::sendAlert);
   }



   /*******************************************************************************
    ** Five eligible results in the preceding 30 days, excluding the new result.
    *******************************************************************************/
   private List<Map<String, Object>> baseline(Map<String, Object> run) throws SQLException
   {
      return (store.query("SELECT * FROM {lab_run} WHERE target_id = ? AND strategy = ? AND status = 'SUCCEEDED'"
         + " AND trigger_type IN ('SCHEDULE', 'CONFIRM', 'SITE_AUDIT') AND completed_at >= ?"
         + " AND (completed_at < ? OR (completed_at = ? AND id < ?)) AND (lighthouse_version = ? OR lighthouse_version LIKE ?)"
         + " ORDER BY completed_at DESC, id DESC FETCH FIRST 5 ROWS ONLY", run.get("target_id"), run.get("strategy"),
         ((Instant) run.get("completed_at")).minus(Duration.ofDays(30)), run.get("completed_at"), run.get("completed_at"), run.get("id"),
         major(run), major(run) + ".%"));
   }



   /*******************************************************************************
    ** Major-version changes are markers and retire incompatible open baselines.
    *******************************************************************************/
   private void versionChange(Map<String, Object> run, List<Long> changed) throws SQLException
   {
      List<Map<String, Object>> previous = store.query("SELECT lighthouse_version FROM {lab_run} WHERE target_id = ? AND strategy = ?"
         + " AND status = 'SUCCEEDED' AND id < ? ORDER BY id DESC FETCH FIRST 1 ROWS ONLY", run.get("target_id"), run.get("strategy"), run.get("id"));
      if(!previous.isEmpty() && !major(previous.get(0)).equals(major(run)))
      {
         new WebPerfChangeService(store).record(new ChangeEventSource.Change("LIGHTHOUSE_VERSION", "LIGHTHOUSE", major(previous.get(0)), major(run),
            (Instant) run.get("completed_at")), "PROBE", "lighthouse:" + major(run), false);
      }
      for(Map<String, Object> open : store.query("SELECT * FROM {regression} WHERE target_id = ? AND strategy = ? AND source = 'LAB'"
         + " AND status IN ('SUSPECTED', 'CONFIRMED') AND lighthouse_major <> ?", run.get("target_id"), run.get("strategy"), major(run)))
      {
         close(open, "CONFIRMED".equals(open.get("status")) ? "RESOLVED" : "NOT_CONFIRMED", "Lighthouse major version changed; baseline reset",
            (Instant) run.get("completed_at"));
         changed.add(WebPerfStore.asLong(open.get("id")));
      }
   }



   /*******************************************************************************
    ** Keep a single open row per page, source, strategy and metric. Its baseline
    ** stays fixed through confirmation and recovery rather than drifting.
    *******************************************************************************/
   private void evaluateMetric(Map<String, Object> target, Map<String, Object> run, String metric, String column,
                               List<Map<String, Object>> baseline, List<Long> changed) throws SQLException
   {
      List<Map<String, Object>> existing = store.query("SELECT * FROM {regression} WHERE target_id = ? AND strategy = ? AND source = 'LAB' AND metric = ?"
         + " AND status IN ('SUSPECTED', 'CONFIRMED') FETCH FIRST 1 ROWS ONLY", target.get("id"), run.get("strategy"), metric);
      if(!existing.isEmpty())
      {
         advance(target, run, metric, column, existing.get(0), changed);
         return;
      }
      Double value = WebPerfStore.asDouble(run.get(column));
      Double reference = baseline.size() == 5 && baseline.stream().allMatch(row -> row.get(column) != null) ? median(baseline, column) : null;
      Map<String, Object> budget = breachedBudget(target, "LAB", (String) run.get("strategy"), metric, value);
      List<String> failedAudits = newFailures(run, metric);
      if(!drift(metric, reference, value) && budget == null && failedAudits.isEmpty())
      {
         return;
      }
      boolean deterministic = metric.endsWith("_SCORE") && !"PERFORMANCE_SCORE".equals(metric);
      Instant at = (Instant) run.get("completed_at");
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("target_id", target.get("id"));
      row.put("content_table", target.get("content_table"));
      row.put("content_id", target.get("content_id"));
      row.put("source", "LAB");
      row.put("strategy", run.get("strategy"));
      row.put("metric", metric);
      row.put("baseline_value", reference);
      row.put("baseline_ref", reference == null ? null : refs(baseline));
      row.put("current_value", value);
      row.put("current_ref", run.get("id").toString());
      row.put("delta", reference == null ? null : value - reference);
      row.put("budget_id", budget == null ? null : budget.get("id"));
      row.put("severity", budget == null ? "WARNING" : budget.get("severity"));
      row.put("status", deterministic ? "CONFIRMED" : "SUSPECTED");
      row.put("detected_at", at);
      row.put("confirmed_at", deterministic ? at : null);
      row.put("cause_change_event_id", run.get("change_event_id"));
      row.put("first_run_id", run.get("id"));
      row.put("checkpoint_run_id", run.get("id"));
      row.put("lighthouse_major", major(run));
      row.put("failed_audits", String.join(",", failedAudits));
      long id = store.insert(WebPerfSchema.REGRESSION, row);
      if(deterministic)
      {
         changed.add(id);
      }
      else
      {
         WebPerfService service = new WebPerfService(new WebPerfRuntime(store.getConfig()), store);
         for(int sample = 0; sample < 2; sample++)
         {
            service.enqueueSample(target, (String) run.get("strategy"), "CONFIRM", "confirm:" + run.get("id") + ":" + sample, at);
         }
      }
   }



   /*******************************************************************************
    ** Confirm with the original plus two explicit samples. Recover only after
    ** three later successful runs on the same major version.
    *******************************************************************************/
   private void advance(Map<String, Object> target, Map<String, Object> run, String metric, String column,
                        Map<String, Object> open, List<Long> changed) throws SQLException
   {
      boolean suspected = "SUSPECTED".equals(open.get("status"));
      List<Map<String, Object>> samples = suspected
         ? store.query("SELECT * FROM {lab_run} WHERE target_id = ? AND strategy = ? AND status = 'SUCCEEDED' AND id <= ?"
            + " AND (id = ? OR trigger_ref IN (?, ?)) AND (lighthouse_version = ? OR lighthouse_version LIKE ?) ORDER BY id FETCH FIRST 3 ROWS ONLY",
            target.get("id"), run.get("strategy"), run.get("id"), open.get("first_run_id"), "confirm:" + open.get("first_run_id") + ":0",
            "confirm:" + open.get("first_run_id") + ":1", major(run), major(run) + ".%")
         : store.query("SELECT * FROM {lab_run} WHERE target_id = ? AND strategy = ? AND status = 'SUCCEEDED' AND id > ? AND id <= ?"
            + " AND (lighthouse_version = ? OR lighthouse_version LIKE ?) ORDER BY id DESC FETCH FIRST 3 ROWS ONLY",
            target.get("id"), run.get("strategy"), open.get("checkpoint_run_id"), run.get("id"), major(run), major(run) + ".%");
      if(samples.size() < 3 || samples.stream().anyMatch(row -> row.get(column) == null))
      {
         return;
      }
      double value = median(samples, column);
      Double reference = WebPerfStore.asDouble(open.get("baseline_value"));
      Map<String, Object> budget = breachedBudget(target, "LAB", (String) run.get("strategy"), metric, value);
      boolean breached = drift(metric, reference, value) || budget != null || auditsStillFail(open, samples);
      Instant at = (Instant) run.get("completed_at");
      store.update("UPDATE {regression} SET current_value = ?, current_ref = ?, delta = ?, budget_id = ?, severity = ?, modify_date = ? WHERE id = ?",
         value, refs(samples), reference == null ? null : value - reference, budget == null ? null : budget.get("id"),
         budget == null ? "WARNING" : budget.get("severity"), at, open.get("id"));
      if(suspected && breached)
      {
         store.update("UPDATE {regression} SET status = 'CONFIRMED', confirmed_at = ?, checkpoint_run_id = ? WHERE id = ?", at, run.get("id"), open.get("id"));
         changed.add(WebPerfStore.asLong(open.get("id")));
      }
      else if(!breached)
      {
         close(open, suspected ? "NOT_CONFIRMED" : "RESOLVED", suspected ? "Median of three did not confirm the regression" : "Recovered: median of three later runs", at);
         changed.add(WebPerfStore.asLong(open.get("id")));
      }
   }



   /*******************************************************************************
    ** Match enabled budgets by source, strategy and every supported scope.
    ** The comparator describes acceptable values; a breach is its inverse.
    *******************************************************************************/
   public Map<String, Object> breachedBudget(Map<String, Object> target, String source, String strategy, String metric, double value) throws SQLException
   {
      Map<String, Object> winner = null;
      long after = 0;
      while(true)
      {
         List<Map<String, Object>> budgets = store.query("SELECT * FROM {budget} WHERE enabled = TRUE AND source = ? AND metric = ? AND id > ?"
            + " AND (strategy IS NULL OR strategy = '' OR strategy = ?) ORDER BY id FETCH FIRST 100 ROWS ONLY", source, metric, after, strategy);
         if(budgets.isEmpty())
         {
            return (winner);
         }
         for(Map<String, Object> budget : budgets)
         {
            after = WebPerfStore.asLong(budget.get("id"));
            if(matches(target, budget) && budget.get("threshold") != null && !acceptable(value, (String) budget.get("comparator"), WebPerfStore.asDouble(budget.get("threshold"))))
            {
               if(winner == null || "CRITICAL".equals(budget.get("severity")))
               {
                  winner = budget;
               }
            }
         }
      }
   }



   /*******************************************************************************
    ** Scope values are literal values, never SQL or wildcard expressions.
    *******************************************************************************/
   private static boolean matches(Map<String, Object> target, Map<String, Object> budget)
   {
      String value = (String) budget.get("scope_value");
      return (switch(String.valueOf(budget.get("scope")))
      {
         case "SITE" -> value == null || value.isBlank() || value.equals(target.get("site_key"));
         case "TIER" -> Objects.equals(value, target.get("tier"));
         case "TARGET" -> Objects.equals(value, String.valueOf(target.get("id")));
         case "CONTENT_TABLE" -> Objects.equals(value, target.get("content_table"));
         case "PATH_PREFIX" -> value != null && target.get("path") instanceof String path && path.startsWith(value);
         default -> false;
      });
   }



   /*******************************************************************************
    ** Supported budget comparators. Invalid records fail visibly on evaluation.
    *******************************************************************************/
   static boolean acceptable(double value, String comparator, double threshold)
   {
      return (switch(String.valueOf(comparator))
      {
         case "<=", "LE" -> value <= threshold;
         case "<", "LT" -> value < threshold;
         case ">=", "GE" -> value >= threshold;
         case ">", "GT" -> value > threshold;
         case "=", "==", "EQ" -> value == threshold;
         case "!=", "<>", "NE" -> value != threshold;
         default -> throw new IllegalArgumentException("Unsupported performance budget comparator: " + comparator);
      });
   }



   /*******************************************************************************
    ** Design section 6.4: relative and absolute LCP limits must both be met.
    *******************************************************************************/
   static boolean drift(String metric, Double baseline, double value)
   {
      if(baseline == null)
      {
         return (false);
      }
      double delta = value - baseline;
      return (switch(metric)
      {
         case "PERFORMANCE_SCORE" -> delta <= -10;
         case "ACCESSIBILITY_SCORE", "BEST_PRACTICES_SCORE", "SEO_SCORE" -> delta <= -5;
         case "LCP" -> delta >= 500 && value >= baseline * 1.25;
         case "CLS" -> delta >= 0.05 - 0.0000001;
         case "TBT" -> delta >= 200;
         default -> false;
      });
   }



   /*******************************************************************************
    ** Newly failing deterministic audits, compared only within the same major.
    *******************************************************************************/
   private List<String> newFailures(Map<String, Object> run, String metric) throws SQLException
   {
      if(!metric.endsWith("_SCORE") || "PERFORMANCE_SCORE".equals(metric))
      {
         return (List.of());
      }
      String category = metric.replace("_SCORE", "");
      List<Map<String, Object>> previous = store.query("SELECT id FROM {lab_run} WHERE target_id = ? AND strategy = ? AND id < ?"
         + " AND status = 'SUCCEEDED' AND (lighthouse_version = ? OR lighthouse_version LIKE ?) ORDER BY id DESC FETCH FIRST 1 ROWS ONLY",
         run.get("target_id"), run.get("strategy"), run.get("id"), major(run), major(run) + ".%");
      if(previous.isEmpty())
      {
         return (List.of());
      }
      return (store.query("SELECT audit_id FROM {lab_finding} f WHERE run_id = ? AND category = ? AND score < 1"
         + " AND NOT EXISTS (SELECT 1 FROM {lab_finding} old WHERE old.run_id = ? AND old.audit_id = f.audit_id AND old.score < 1)"
         + " ORDER BY audit_id FETCH FIRST 19 ROWS ONLY", run.get("id"), category, previous.get(0).get("id"))
         .stream().map(row -> (String) row.get("audit_id")).toList());
   }



   /*******************************************************************************
    ** An audit-caused regression recovers when a majority no longer fail it.
    *******************************************************************************/
   private boolean auditsStillFail(Map<String, Object> open, List<Map<String, Object>> samples) throws SQLException
   {
      String audits = (String) open.get("failed_audits");
      if(audits == null || audits.isBlank())
      {
         return (false);
      }
      for(String audit : audits.split(","))
      {
         int failed = 0;
         for(Map<String, Object> sample : samples)
         {
            if(!store.query("SELECT id FROM {lab_finding} WHERE run_id = ? AND audit_id = ? AND score < 1 FETCH FIRST 1 ROWS ONLY", sample.get("id"), audit).isEmpty())
            {
               failed++;
            }
         }
         if(failed >= 2)
         {
            return (true);
         }
      }
      return (false);
   }



   /*******************************************************************************
    ** Record the terminal state without discarding evidence.
    *******************************************************************************/
   private void close(Map<String, Object> regression, String status, String note, Instant at) throws SQLException
   {
      store.update("UPDATE {regression} SET status = ?, resolved_at = ?, resolution_note = ?, modify_date = ? WHERE id = ?",
         status, at, note, at, regression.get("id"));
   }



   /*******************************************************************************
    ** Record action; rechecks state under the same target lock as evaluation.
    *******************************************************************************/
   public boolean finishRegression(long id, String status, String note, Instant at) throws SQLException
   {
      if(!List.of("DISMISSED", "RESOLVED").contains(status) || note == null || note.isBlank() || note.length() > 2000)
      {
         throw (new IllegalArgumentException("A resolution note of 1 to 2000 characters is required"));
      }
      boolean changed = store.transaction(tx ->
      {
         List<Map<String, Object>> rows = tx.query("SELECT * FROM {regression} WHERE id = ?", id);
         if(rows.isEmpty())
         {
            return (false);
         }
         Map<String, Object> row = rows.get(0);
         tx.query("SELECT id FROM {target} WHERE id = ? FOR UPDATE", row.get("target_id"));
         int updated = tx.update("UPDATE {regression} SET status = ?, resolved_at = ?, resolution_note = ?, modify_date = ? WHERE id = ? AND status IN ('SUSPECTED', 'CONFIRMED')",
            status, at, note, at, id);
         new WebPerfEvaluationService(tx).updateCount(row.get("target_id"));
         return (updated == 1);
      });
      if(changed)
      {
         sendAlert(id);
      }
      return (changed);
   }



   /*******************************************************************************
    ** The list count includes suspected and confirmed open regressions.
    *******************************************************************************/
   private void updateCount(Object targetId) throws SQLException
   {
      store.update("UPDATE {target} SET open_regression_count = (SELECT COUNT(*) FROM {regression} WHERE target_id = ? AND status IN ('SUSPECTED', 'CONFIRMED')) WHERE id = ?",
         targetId, targetId);
   }



   /*******************************************************************************
    ** Optional core producer API; without an Alerts QBit core uses its log sink.
    *******************************************************************************/
   private void sendAlert(long id)
   {
      if(!store.getConfig().isOn(WebPerfFeature.ALERTS))
      {
         return;
      }
      try
      {
         Map<String, Object> row = store.query("SELECT * FROM {regression} WHERE id = ?", id).get(0);
         String key = store.getConfig().getTableNamePrefix() + ":" + row.get("target_id") + ":" + row.get("source") + ":" + row.get("strategy") + ":" + row.get("metric");
         if("CONFIRMED".equals(row.get("status")))
         {
            QAlerts.raise(new AlertSignal(ALERT_TYPE).withDedupKey(key).withSeverity(AlertSeverity.valueOf((String) row.get("severity")))
               .withTitle("Confirmed web performance regression").withSummary(row.get("strategy") + " " + row.get("metric"))
               .withRecordRef(AlertRecordRef.of(store.getConfig().applyPrefix(WebPerfSchema.REGRESSION), id)));
         }
         else if(List.of("RESOLVED", "DISMISSED").contains(row.get("status")))
         {
            QAlerts.resolve(ALERT_TYPE, key, "Performance regression " + row.get("status").toString().toLowerCase(Locale.ROOT));
         }
      }
      catch(SQLException e)
      {
         throw (new IllegalStateException("Reading a committed performance regression failed", e));
      }
   }



   /*******************************************************************************
    ** Median of bounded, non-null measurement values.
    *******************************************************************************/
   static Double median(List<Map<String, Object>> rows, String column)
   {
      List<Double> values = rows.stream().map(row -> WebPerfStore.asDouble(row.get(column))).filter(Objects::nonNull).sorted().toList();
      int size = values.size();
      return (size == 0 ? null : size % 2 == 1 ? values.get(size / 2) : (values.get(size / 2 - 1) + values.get(size / 2)) / 2);
   }



   /*******************************************************************************
    ** Stored ids make a statistical result reproducible.
    *******************************************************************************/
   static String refs(List<Map<String, Object>> rows)
   {
      return (String.join(",", rows.stream().map(row -> row.get("id").toString()).toList()));
   }



   /*******************************************************************************
    ** Unknown versions never mix with a known Lighthouse baseline.
    *******************************************************************************/
   static String major(Map<String, Object> run)
   {
      return (String.valueOf(run.get("lighthouse_version")).split("\\.")[0]);
   }
}
