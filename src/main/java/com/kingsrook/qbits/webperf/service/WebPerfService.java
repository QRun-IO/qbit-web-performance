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


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;
import com.kingsrook.qbits.webperf.WebPerfQBitConfig;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qbits.webperf.field.CruxClient;
import com.kingsrook.qbits.webperf.lab.LabResultParser;
import com.kingsrook.qbits.webperf.spi.ContentResolver;
import com.kingsrook.qbits.webperf.spi.LabRunner;
import com.kingsrook.qbits.webperf.store.WebPerfStore;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import org.json.JSONObject;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The QBit's operations (design §6): syncing tested pages from the URL
 ** source, queueing lab runs (merging duplicates, honoring the daily cap),
 ** running due runs through the lab runner (claim, retry with backoff, store
 ** scores, findings, the full report and the page's latest results), and
 ** refreshing Google field data (CrUX, NO_DATA kept as a real result).
 **
 ** Claims are a conditional update (QUEUED to RUNNING for one id), so any
 ** number of backend replicas never run the same test twice.
 *******************************************************************************/
public class WebPerfService
{
   private static final QLogger LOG = QLogger.getLogger(WebPerfService.class);

   public static final int        MAX_ATTEMPTS = 3;
   public static final Duration[] BACKOFF      = { Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15) };

   /** Statuses that still hold a place in the queue (a new request merges into them). */
   public static final List<String> OPEN_STATUSES = List.of("QUEUED", "RUNNING");

   static final int REPORT_BATCH       = 500;
   static final int REPORT_MAX_BATCHES = 20;

   private final WebPerfRuntime    runtime;
   private final WebPerfStore      store;
   private final WebPerfQBitConfig config;



   /*******************************************************************************
    ** A service over a runtime's store.
    **
    ** @param runtime the runtime
    ** @param store   the store
    *******************************************************************************/
   public WebPerfService(WebPerfRuntime runtime, WebPerfStore store)
   {
      this.runtime = runtime;
      this.store = store;
      this.config = runtime.getConfig();
   }



   /*******************************************************************************
    ** The site key of the configured site ("host:www.example.com").
    **
    ** @return the key
    *******************************************************************************/
   public String siteKey()
   {
      return ("host:" + URI.create(config.getSiteBaseUrl()).getHost().toLowerCase(Locale.ROOT));
   }



   /////////////////////////////////////////////////////////////////////////////
   // tested pages                                                             //
   /////////////////////////////////////////////////////////////////////////////

   /*******************************************************************************
    ** What a sync will do.
    **
    ** @param newUrls     URLs to add
    ** @param seenIds     existing pages still listed
    ** @param removedIds  pages no longer listed (kept, marked not in the sitemap)
    ** @param pathsByUrl  each listed URL's path
    *******************************************************************************/
   public record SyncPlan(List<String> newUrls, List<Long> seenIds, List<Long> removedIds, Map<String, String> pathsByUrl)
   {
   }



   /*******************************************************************************
    ** Plan a sync against the listed URLs (reads only).
    **
    ** @param urls the URLs from the URL source
    ** @return the plan
    ** @throws SQLException on failure
    *******************************************************************************/
   public SyncPlan planSync(List<String> urls) throws SQLException
   {
      Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
      for(Map<String, Object> row : store.query("SELECT id, url, source, in_sitemap FROM {target} WHERE site_key = ?", siteKey()))
      {
         existing.put((String) row.get("url"), row);
      }

      List<String>        newUrls    = new ArrayList<>();
      List<Long>          seen       = new ArrayList<>();
      Map<String, String> paths      = new LinkedHashMap<>();
      Set<String>         listed     = new HashSet<>();
      for(String url : urls)
      {
         if(!listed.add(url))
         {
            continue;
         }
         paths.put(url, pathOf(url));
         Map<String, Object> row = existing.get(url);
         if(row == null)
         {
            newUrls.add(url);
         }
         else
         {
            seen.add(WebPerfStore.asLong(row.get("id")));
         }
      }

      List<Long> removed = new ArrayList<>();
      for(Map.Entry<String, Map<String, Object>> entry : existing.entrySet())
      {
         Map<String, Object> row = entry.getValue();
         if(!listed.contains(entry.getKey()) && "SITEMAP".equals(row.get("source")) && !Boolean.FALSE.equals(row.get("in_sitemap")))
         {
            removed.add(WebPerfStore.asLong(row.get("id")));
         }
      }
      return (new SyncPlan(newUrls, seen, removed, paths));
   }



   /*******************************************************************************
    ** Apply a sync plan: add new pages (linked to their records, KEY when
    ** their path is a key path), refresh listed ones, mark unlisted ones as
    ** not in the sitemap. Nothing is deleted.
    **
    ** @param plan the plan
    ** @param now  the time
    ** @throws SQLException on failure
    *******************************************************************************/
   public void applySync(SyncPlan plan, Instant now) throws SQLException
   {
      ContentResolver resolver = config.getContentResolver();
      for(String url : plan.newUrls())
      {
         String                      path    = plan.pathsByUrl().get(url);
         ContentResolver.ContentRef  content = resolver == null ? null : resolver.resolve(path);
         Map<String, Object>         values  = new LinkedHashMap<>();
         values.put("site_key", siteKey());
         values.put("url", url);
         values.put("path", path);
         values.put("source", "SITEMAP");
         values.put("content_table", content == null ? null : content.tableName());
         values.put("content_id", content == null ? null : content.id());
         values.put("tier", isKeyPath(path) ? "KEY" : "STANDARD");
         values.put("enabled", true);
         values.put("in_sitemap", true);
         values.put("last_seen_in_sitemap_at", now);
         store.insert(WebPerfSchema.TARGET, values);
      }
      for(Long id : plan.seenIds())
      {
         store.update("UPDATE {target} SET in_sitemap = ?, last_seen_in_sitemap_at = ?, modify_date = ? WHERE id = ?", true, now, now, id);
      }
      for(Long id : plan.removedIds())
      {
         store.update("UPDATE {target} SET in_sitemap = ?, modify_date = ? WHERE id = ?", false, now, id);
      }
   }



   /*******************************************************************************
    ** Whether a path is one of the configured key paths (a trailing slash is
    ** ignored, except for the root).
    *******************************************************************************/
   boolean isKeyPath(String path)
   {
      String normal = normalizePath(path);
      return (config.getKeyPaths().stream().anyMatch(p -> normalizePath(p).equals(normal)));
   }



   /*******************************************************************************
    ** A URL's path ("/" when empty).
    *******************************************************************************/
   static String pathOf(String url)
   {
      String path = URI.create(url).getPath();
      return (path == null || path.isEmpty() ? "/" : path);
   }



   /*******************************************************************************
    ** A path without its trailing slash (the root stays "/").
    *******************************************************************************/
   static String normalizePath(String path)
   {
      if(path == null || path.isEmpty())
      {
         return ("/");
      }
      return (path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
   }



   /////////////////////////////////////////////////////////////////////////////
   // queue                                                                    //
   /////////////////////////////////////////////////////////////////////////////

   /*******************************************************************************
    ** One run to queue.
    **
    ** @param targetId   the page
    ** @param url        its URL
    ** @param strategy   MOBILE or DESKTOP
    ** @param trigger    SCHEDULE, PUBLISH, DEPLOY, MANUAL, SITE_AUDIT, CONFIRM or CHANGE_COMPARE
    ** @param triggerRef what caused it (may be null)
    ** @param batchId    groups the runs of one action or schedule
    ** @param notBefore  earliest start
    *******************************************************************************/
   public record RunRequest(Long targetId, String url, String strategy, String trigger, String triggerRef, String batchId, Instant notBefore)
   {
   }



   /*******************************************************************************
    ** How a set of requests would be or was queued.
    **
    ** @param queue       requests queued
    ** @param merged      requests already queued or running for that page and strategy
    ** @param overQuota   requests past today's run cap (recorded as SKIPPED_QUOTA)
    ** @param runsToday   runs already counted today
    *******************************************************************************/
   public record QueuePlan(List<RunRequest> queue, List<RunRequest> merged, List<RunRequest> overQuota, int runsToday)
   {
   }



   /*******************************************************************************
    ** Plan queueing (reads only): merge into open runs, then apply the cap.
    **
    ** @param requests the requests
    ** @param now      the time
    ** @return the plan
    ** @throws SQLException on failure
    *******************************************************************************/
   public QueuePlan planQueue(List<RunRequest> requests, Instant now) throws SQLException
   {
      Set<String> open = new HashSet<>();
      int runsToday = runsSince(startOfToday(now));

      List<RunRequest> queue     = new ArrayList<>();
      List<RunRequest> merged    = new ArrayList<>();
      List<RunRequest> overQuota = new ArrayList<>();
      for(RunRequest request : requests)
      {
         String key = request.targetId() + "|" + request.strategy();
         if(!open.add(key) || !store.query("SELECT id FROM {lab_run} WHERE target_id = ? AND strategy = ? AND status IN ('QUEUED', 'RUNNING') FETCH FIRST 1 ROWS ONLY",
            request.targetId(), request.strategy()).isEmpty())
         {
            merged.add(request);
         }
         else if(runsToday + queue.size() >= config.getMaxRunsPerDay())
         {
            overQuota.add(request);
         }
         else
         {
            queue.add(request);
         }
      }
      return (new QueuePlan(queue, merged, overQuota, runsToday));
   }



   /*******************************************************************************
    ** Queue requests: plan again at write time (so concurrent actions still
    ** merge), insert QUEUED runs, and record over-cap ones as SKIPPED_QUOTA.
    **
    ** @param requests the requests
    ** @param now      the time
    ** @return the plan that was applied
    ** @throws SQLException on failure
    *******************************************************************************/
   public QueuePlan enqueue(List<RunRequest> requests, Instant now) throws SQLException
   {
      QueuePlan plan = planQueue(requests, now);
      for(RunRequest request : plan.queue())
      {
         store.insert(WebPerfSchema.LAB_RUN, runValues(request, "QUEUED", now, null));
      }
      for(RunRequest request : plan.overQuota())
      {
         store.insert(WebPerfSchema.LAB_RUN, runValues(request, "SKIPPED_QUOTA", now,
            "Today's run cap (" + config.getMaxRunsPerDay() + ") was reached; use Retry to queue it again"));
      }
      return (plan);
   }



   /*******************************************************************************
    ** Queue an independent confirmation or comparison sample. Its reference is
    ** stable across retries; deliberate samples must not merge with each other.
    *******************************************************************************/
   public String enqueueSample(Map<String, Object> target, String strategy, String trigger, String reference, Instant due) throws SQLException
   {
      if(!store.query("SELECT id FROM {lab_run} WHERE target_id = ? AND strategy = ? AND trigger_ref = ? AND status NOT IN ('FAILED', 'RUNTIME_ERROR', 'SKIPPED_QUOTA') FETCH FIRST 1 ROWS ONLY",
         target.get("id"), strategy, reference).isEmpty())
      {
         return ("MERGED");
      }
      Instant now = Instant.now();
      boolean capped = runsSince(startOfToday(now)) >= config.getMaxRunsPerDay();
      RunRequest request = new RunRequest(WebPerfStore.asLong(target.get("id")), (String) target.get("url"), strategy, trigger, reference, null, due);
      store.insert(WebPerfSchema.LAB_RUN, runValues(request, capped ? "SKIPPED_QUOTA" : "QUEUED", now,
         capped ? "Daily run cap reached; use Retry to collect this sample" : null));
      return (capped ? "SKIPPED_QUOTA" : "QUEUED");
   }



   /*******************************************************************************
    ** Retry a deliberate sample without losing its confirmation/comparison slot.
    ** Keep the failed row as history; simultaneous retries share the target lock.
    *******************************************************************************/
   public String retrySample(long runId, Instant now) throws SQLException
   {
      return (store.transaction(tx ->
      {
         Map<String, Object> run = tx.query("SELECT * FROM {lab_run} WHERE id = ?", runId).get(0);
         Map<String, Object> target = tx.query("SELECT id, url FROM {target} WHERE id = ? FOR UPDATE", run.get("target_id")).get(0);
         if(!List.of("FAILED", "RUNTIME_ERROR", "SKIPPED_QUOTA").contains(run.get("status")))
         {
            return ("MERGED");
         }
         return (new WebPerfService(runtime, tx).enqueueSample(target, (String) run.get("strategy"), (String) run.get("trigger_type"),
            (String) run.get("trigger_ref"), now));
      }));
   }



   /*******************************************************************************
    ** The column values of a new run.
    *******************************************************************************/
   private Map<String, Object> runValues(RunRequest request, String status, Instant now, String error)
   {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("target_id", request.targetId());
      values.put("url", request.url());
      values.put("strategy", request.strategy());
      values.put("runner", runtime.labRunner() == null ? null : runtime.labRunner().name());
      values.put("trigger_type", request.trigger());
      values.put("trigger_ref", request.triggerRef());
      values.put("batch_id", request.batchId());
      values.put("status", status);
      values.put("not_before", request.notBefore() == null ? now : request.notBefore());
      values.put("queued_at", now);
      values.put("attempts", 0);
      values.put("error_message", error);
      return (values);
   }



   /*******************************************************************************
    ** Requests for pages: one per configured strategy.
    **
    ** @param targets   rows with id and url
    ** @param trigger   the trigger
    ** @param triggerRef what caused it
    ** @param notBefore earliest start
    ** @return the requests
    *******************************************************************************/
   public List<RunRequest> requestsFor(List<Map<String, Object>> targets, String trigger, String triggerRef, Instant notBefore)
   {
      String           batch    = UUID.randomUUID().toString();
      List<RunRequest> requests = new ArrayList<>();
      for(Map<String, Object> target : targets)
      {
         for(String strategy : config.getStrategies())
         {
            requests.add(new RunRequest(WebPerfStore.asLong(target.get("id")), (String) target.get("url"), strategy, trigger, triggerRef, batch, notBefore));
         }
      }
      return (requests);
   }



   /*******************************************************************************
    ** Enabled pages, optionally only the KEY tier.
    **
    ** @param keyOnly only KEY pages
    ** @return rows with id and url
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> enabledTargets(boolean keyOnly) throws SQLException
   {
      return (store.query("SELECT id, url FROM {target} WHERE enabled = TRUE AND site_key = ?" + (keyOnly ? " AND tier = 'KEY'" : "") + " ORDER BY id",
         siteKey()));
   }



   /*******************************************************************************
    ** Runs counted against today's cap (queued since the start of today,
    ** excluding cap skips).
    *******************************************************************************/
   int runsSince(Instant since) throws SQLException
   {
      return (WebPerfStore.asInt(store.query("SELECT COUNT(*) AS n FROM {lab_run} WHERE queued_at >= ? AND status <> 'SKIPPED_QUOTA'", since)
         .get(0).get("n")));
   }



   /*******************************************************************************
    ** The start of today in the configured zone.
    *******************************************************************************/
   Instant startOfToday(Instant now)
   {
      ZoneId zone = ZoneId.of(config.getTimeZone());
      return (now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant());
   }



   /////////////////////////////////////////////////////////////////////////////
   // worker                                                                   //
   /////////////////////////////////////////////////////////////////////////////

   /*******************************************************************************
    ** Run due queued tests: claim up to `concurrency` of them and run them in
    ** parallel. Without a lab runner (no Google API key) nothing is claimed;
    ** the runs wait, and the screens say why.
    **
    ** @param now the time
    ** @return the runs finished (in any final or retry state)
    ** @throws Exception on a store failure
    *******************************************************************************/
   public int runDue(Instant now) throws Exception
   {
      new WebPerfEvaluationService(store).evaluatePending();
      LabRunner runner = runtime.labRunner();
      if(runner == null)
      {
         LOG.info("Web performance: no lab runner (no Google API key); queued runs wait");
         return (0);
      }

      List<Map<String, Object>> due = store.query("SELECT id FROM {lab_run} WHERE status = 'QUEUED' AND not_before <= ? ORDER BY not_before, id FETCH FIRST " + config.getConcurrency() + " ROWS ONLY", now);
      List<Long>                claimed = new ArrayList<>();
      for(Map<String, Object> row : due)
      {
         if(claimed.size() >= config.getConcurrency())
         {
            break;
         }
         Long id = WebPerfStore.asLong(row.get("id"));
         if(store.update("UPDATE {lab_run} SET status = 'RUNNING', started_at = ?, modify_date = ? WHERE id = ? AND status = 'QUEUED'", now, now, id) == 1)
         {
            claimed.add(id);
         }
      }
      if(claimed.isEmpty())
      {
         return (0);
      }

      ExecutorService executor = Executors.newFixedThreadPool(claimed.size());
      try
      {
         List<Future<?>> futures = new ArrayList<>();
         for(int i = 0; i < claimed.size(); i++)
         {
            long id    = claimed.get(i);
            long delay = (long) i * config.getMinSpacingMillis();
            futures.add(executor.submit(() ->
            {
               if(delay > 0)
               {
                  Thread.sleep(delay);
               }
               runOne(runner, id);
               return (null);
            }));
         }
         for(Future<?> future : futures)
         {
            future.get();
         }
      }
      finally
      {
         executor.shutdownNow();
      }
      new WebPerfEvaluationService(store).evaluatePending();
      return (claimed.size());
   }



   /*******************************************************************************
    ** Run one claimed test and record what happened.
    **
    ** @param runner the lab runner
    ** @param runId  the claimed run
    ** @throws SQLException on a store failure
    *******************************************************************************/
   void runOne(LabRunner runner, long runId) throws SQLException
   {
      Map<String, Object> run      = store.query("SELECT id, target_id, url, strategy, attempts FROM {lab_run} WHERE id = ?", runId).get(0);
      int                 attempts = WebPerfStore.asInt(run.get("attempts")) + 1;
      new WebPerfChangeService(store).attribute(runId, Instant.now());
      LabRunner.LabResponse response;
      try
      {
         response = runner.run(new LabRunner.LabRequest((String) run.get("url"), (String) run.get("strategy"), Set.copyOf(config.getCategories())));
      }
      catch(Exception e)
      {
         retryOrFail(runId, attempts, null, e.getMessage());
         return;
      }

      int status = response.httpStatus();
      if(status == 429 || status >= 500)
      {
         retryOrFail(runId, attempts, status, apiError(response.rawJson(), status));
         return;
      }
      if(status != 200)
      {
         finish(runId, attempts, "FAILED", status, apiError(response.rawJson(), status), response.completedAt());
         return;
      }

      LabResultParser.ParsedLab parsed;
      try
      {
         parsed = LabResultParser.parse(response.rawJson());
      }
      catch(RuntimeException e)
      {
         finish(runId, attempts, "FAILED", status, "The response could not be read: " + e.getMessage(), response.completedAt());
         return;
      }
      store(runId, run, attempts, parsed, response);
   }



   /*******************************************************************************
    ** Apply reportRetentionDays: delete Lighthouse Reports older than that many
    ** days (0 keeps them forever). The run, its scores and findings stay; only
    ** the full compressed response goes. Runs after a result is committed (a
    ** failure here never loses a run's evidence), at most once an hour per
    ** process (WebPerfRuntime.reportPruneDue, shared by the workers), in batches
    ** of REPORT_BATCH rows, at most REPORT_MAX_BATCHES per pass. There is no
    ** table for state shared across replicas, so each replica keeps its own
    ** hourly limit; passes are bounded and idempotent, so overlapping replicas
    ** only repeat one cheap query.
    *******************************************************************************/
   void pruneReports()
   {
      int days = runtime.getConfig().getReportRetentionDays();
      if(days <= 0 || !runtime.reportPruneDue())
      {
         return;
      }

      try
      {
         java.sql.Timestamp cutoff = WebPerfStore.utc(Instant.now().minus(java.time.Duration.ofDays(days)));
         for(int batch = 0; batch < REPORT_MAX_BATCHES; batch++)
         {
            int deleted = store.update("DELETE FROM {lab_report} WHERE id IN (SELECT id FROM {lab_report} WHERE create_date < ? ORDER BY id FETCH FIRST " + REPORT_BATCH + " ROWS ONLY)", cutoff);
            if(deleted < REPORT_BATCH)
            {
               return;
            }
         }
      }
      catch(SQLException | RuntimeException e)
      {
         LOG.warn("Could not prune old Lighthouse Reports", e);
      }
   }



   /*******************************************************************************
    ** Store a successful response: the run's scores and metrics, its findings,
    ** the compressed report, and the page's latest results.
    *******************************************************************************/
   private void store(long runId, Map<String, Object> run, int attempts, LabResultParser.ParsedLab parsed, LabRunner.LabResponse response) throws SQLException
   {
      store.transaction(tx ->
      {
         new WebPerfService(runtime, tx).storeResult(runId, run, attempts, parsed, response);
         return (null);
      });
      pruneReports();
   }



   /*******************************************************************************
    ** Commit the result, findings, report and latest values as one unit, so an
    ** evaluator never sees a successful run with only some of its evidence.
    *******************************************************************************/
   private void storeResult(long runId, Map<String, Object> run, int attempts, LabResultParser.ParsedLab parsed, LabRunner.LabResponse response) throws SQLException
   {
      Instant completed = response.completedAt();
      String  status    = parsed.runtimeError() == null ? "SUCCEEDED" : "RUNTIME_ERROR";
      store.update("""
            UPDATE {lab_run} SET status = ?, completed_at = ?, attempts = ?, http_status = ?, error_message = ?, final_url = ?, lighthouse_version = ?,
               chrome_user_agent = ?, benchmark_index = ?, score_performance = ?, score_accessibility = ?, score_best_practices = ?, score_seo = ?,
               fcp_ms = ?, lcp_ms = ?, tbt_ms = ?, cls = ?, speed_index_ms = ?, lcp_element = ?, run_warnings = ?, modify_date = ?
            WHERE id = ?""",
         status, completed, attempts, response.httpStatus(), parsed.runtimeError(), parsed.finalUrl(), parsed.lighthouseVersion(),
         parsed.chromeUserAgent(), parsed.benchmarkIndex(), parsed.score("PERFORMANCE"), parsed.score("ACCESSIBILITY"), parsed.score("BEST_PRACTICES"),
         parsed.score("SEO"), parsed.fcpMs(), parsed.lcpMs(), parsed.tbtMs(), parsed.cls(), parsed.speedIndexMs(), parsed.lcpElement(), parsed.runWarnings(),
         completed, runId);

      store.update("UPDATE {lab_run} SET ttfb_ms = ? WHERE id = ?", LabResultParser.ttfb(response.rawJson()), runId);
      Long targetId = WebPerfStore.asLong(run.get("target_id"));
      for(LabResultParser.Finding finding : parsed.findings())
      {
         Map<String, Object> values = new LinkedHashMap<>();
         values.put("run_id", runId);
         values.put("target_id", targetId);
         values.put("strategy", run.get("strategy"));
         values.put("category", finding.category());
         values.put("audit_id", finding.auditId());
         values.put("lighthouse_version", parsed.lighthouseVersion());
         values.put("kind", finding.kind());
         values.put("title", finding.title());
         values.put("score", finding.score());
         values.put("display_value", finding.displayValue());
         values.put("savings_ms", finding.savingsMs());
         values.put("savings_bytes", finding.savingsBytes());
         values.put("metric_savings", finding.metricSavings());
         values.put("top_items", finding.topItems());
         store.insert(WebPerfSchema.LAB_FINDING, values);
      }

      byte[]              raw    = response.rawJson().getBytes(StandardCharsets.UTF_8);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("run_id", runId);
      report.put("content", gzip(raw));
      report.put("bytes", raw.length);
      report.put("sha256", sha256(raw));
      store.insert(WebPerfSchema.LAB_REPORT, report);

      if("SUCCEEDED".equals(status) && targetId != null)
      {
         String column = "MOBILE".equals(run.get("strategy")) ? "latest_mobile_performance" : "latest_desktop_performance";
         store.update("UPDATE {target} SET " + column + " = ?, latest_accessibility = ?, latest_tested_at = ?, modify_date = ? WHERE id = ?",
            parsed.score("PERFORMANCE"), parsed.score("ACCESSIBILITY"), completed, completed, targetId);
      }
      LOG.info("Web performance run finished", logPair("runId", runId), logPair("status", status), logPair("performance", parsed.score("PERFORMANCE")));
   }



   /*******************************************************************************
    ** Queue a run again with backoff, or fail it after MAX_ATTEMPTS.
    *******************************************************************************/
   private void retryOrFail(long runId, int attempts, Integer httpStatus, String error) throws SQLException
   {
      Instant now = Instant.now();
      if(attempts >= MAX_ATTEMPTS)
      {
         finish(runId, attempts, "FAILED", httpStatus, error, now);
         return;
      }
      store.update("UPDATE {lab_run} SET status = 'QUEUED', attempts = ?, http_status = ?, error_message = ?, not_before = ?, modify_date = ? WHERE id = ?",
         attempts, httpStatus, truncate(error), now.plus(BACKOFF[attempts - 1]), now, runId);
   }



   /*******************************************************************************
    ** Record a final status without results.
    *******************************************************************************/
   private void finish(long runId, int attempts, String status, Integer httpStatus, String error, Instant completed) throws SQLException
   {
      store.update("UPDATE {lab_run} SET status = ?, attempts = ?, http_status = ?, error_message = ?, completed_at = ?, modify_date = ? WHERE id = ?",
         status, attempts, httpStatus, truncate(error), completed, completed, runId);
   }



   /*******************************************************************************
    ** The API's own error message, or the status.
    *******************************************************************************/
   static String apiError(String body, int status)
   {
      try
      {
         JSONObject error = new JSONObject(body).optJSONObject("error");
         if(error != null && error.has("message"))
         {
            return ("HTTP " + status + ": " + error.getString("message"));
         }
      }
      catch(RuntimeException e)
      {
         // not JSON
      }
      return ("HTTP " + status);
   }



   /////////////////////////////////////////////////////////////////////////////
   // Google field data                                                        //
   /////////////////////////////////////////////////////////////////////////////

   /*******************************************************************************
    ** Query CrUX for keys and store the results. A NO_DATA result updates the
    ** existing NO_DATA row's last check (one row, not one a day); data is
    ** stored once per collection period.
    **
    ** @param keys scope ("URL" or "ORIGIN") to the keys of that scope
    ** @param now  the time
    ** @return result status to count
    ** @throws Exception on a store or transport failure
    *******************************************************************************/
   public Map<String, Integer> refreshFieldData(Map<String, List<String>> keys, Instant now) throws Exception
   {
      CruxClient client = runtime.cruxClient();
      if(client == null)
      {
         throw (new IllegalStateException("No Google API key is configured, so Google field data cannot be checked"));
      }
      Map<String, Integer> counts = new LinkedHashMap<>();
      boolean              first  = true;
      for(Map.Entry<String, List<String>> scope : keys.entrySet())
      {
         for(String key : scope.getValue())
         {
            for(String formFactor : CruxClient.FORM_FACTORS)
            {
               //////////////////////////////////////////////////////////////
               // pace the queries under the API's 150 a minute per project //
               //////////////////////////////////////////////////////////////
               if(!first && config.getCruxSpacingMillis() > 0)
               {
                  Thread.sleep(config.getCruxSpacingMillis());
               }
               first = false;
               CruxClient.CruxResult result = client.query(scope.getKey(), key, formFactor);
               storeField(result, now);
               counts.merge(result.status(), 1, Integer::sum);
            }
         }
      }
      new WebPerfEvaluationService(store).evaluatePending();
      return (counts);
   }



   /*******************************************************************************
    ** Upsert one CrUX result.
    *******************************************************************************/
   void storeField(CruxClient.CruxResult result, Instant now) throws SQLException
   {
      List<Map<String, Object>> existing = "NO_DATA".equals(result.status())
         ? store.query("SELECT id FROM {field_snapshot} WHERE source = 'CRUX_API' AND scope = ? AND scope_key = ? AND form_factor = ? AND status = 'NO_DATA'",
            result.scope(), result.key(), result.formFactor())
         : store.query("SELECT id FROM {field_snapshot} WHERE source = 'CRUX_API' AND scope = ? AND scope_key = ? AND form_factor = ? AND period_end = ?",
            result.scope(), result.key(), result.formFactor(), result.periodEnd());
      if(!existing.isEmpty())
      {
         store.update("UPDATE {field_snapshot} SET last_checked_at = ?, modify_date = ? WHERE id = ?", now, now, existing.get(0).get("id"));
         return;
      }
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("source", "CRUX_API");
      values.put("scope", result.scope());
      values.put("scope_key", result.key());
      values.put("form_factor", result.formFactor());
      values.put("status", result.status());
      values.put("period_start", result.periodStart());
      values.put("period_end", result.periodEnd());
      values.put("p75_lcp_ms", result.p75LcpMs());
      values.put("p75_inp_ms", result.p75InpMs());
      values.put("p75_cls", result.p75Cls());
      values.put("p75_fcp_ms", result.p75FcpMs());
      values.put("p75_ttfb_ms", result.p75TtfbMs());
      values.put("distributions", result.distributions());
      values.put("last_checked_at", now);
      store.insert(WebPerfSchema.FIELD_SNAPSHOT, values);
   }



   /*******************************************************************************
    ** The site's origin ("https://www.example.com").
    **
    ** @return the origin
    *******************************************************************************/
   public String origin()
   {
      URI uri = URI.create(config.getSiteBaseUrl());
      return (uri.getScheme() + "://" + uri.getAuthority());
   }



   /////////////////////////////////////////////////////////////////////////////
   // helpers                                                                  //
   /////////////////////////////////////////////////////////////////////////////

   /*******************************************************************************
    ** Gzip bytes.
    *******************************************************************************/
   static byte[] gzip(byte[] bytes)
   {
      try(ByteArrayOutputStream out = new ByteArrayOutputStream(); GZIPOutputStream gzip = new GZIPOutputStream(out))
      {
         gzip.write(bytes);
         gzip.finish();
         return (out.toByteArray());
      }
      catch(IOException e)
      {
         throw (new IllegalStateException("gzip failed", e));
      }
   }



   /*******************************************************************************
    ** Hex SHA-256.
    *******************************************************************************/
   static String sha256(byte[] bytes)
   {
      try
      {
         return (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
      catch(Exception e)
      {
         throw (new IllegalStateException("SHA-256 unavailable", e));
      }
   }



   /*******************************************************************************
    ** Cut an error message to the column size.
    *******************************************************************************/
   private static String truncate(String text)
   {
      return (text == null || text.length() <= 2000 ? text : text.substring(0, 2000));
   }



   /*******************************************************************************
    ** Getter for store.
    **
    ** @return the store
    *******************************************************************************/
   public WebPerfStore getStore()
   {
      return (store);
   }
}
