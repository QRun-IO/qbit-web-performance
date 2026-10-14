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

package com.kingsrook.qbits.webperf;


import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import com.kingsrook.qbits.webperf.lab.LabResultParser;
import com.kingsrook.qbits.webperf.processes.RegressionSteps;
import com.kingsrook.qbits.webperf.processes.WebPerfProcesses;
import com.kingsrook.qbits.webperf.service.WebPerfChangeService;
import com.kingsrook.qbits.webperf.service.WebPerfComparisonService;
import com.kingsrook.qbits.webperf.service.WebPerfEvaluationService;
import com.kingsrook.qbits.webperf.service.WebPerfService;
import com.kingsrook.qbits.webperf.spi.ChangeEventSource;
import com.kingsrook.qbits.webperf.spi.DeployProbe;
import com.kingsrook.qbits.webperf.spi.LabRunner;
import com.kingsrook.qbits.webperf.store.WebPerfStore;
import com.kingsrook.qqq.backend.core.actions.processes.QProcessCallback;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.alerts.AlertResolution;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.AlertSinkInterface;
import com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlerts;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;


/*******************************************************************************
 ** P2 behavior on H2, with fake probes and runner and real QQQ record actions.
 *******************************************************************************/
public class WebPerfRegressionTest
{
   private static final Instant START = Instant.parse("2026-10-01T12:00:00Z");
   private QInstance instance;
   private WebPerfQBitConfig config;
   private WebPerfStore store;
   private WebPerfEvaluationService evaluator;
   private long target;
   private int sequence;



   /*******************************************************************************
    ** Fresh tables; disable reference budgets to isolate statistical rules.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      instance = WebPerfTestSupport.newInstance();
      config = WebPerfTestSupport.config().withStrategies(List.of("MOBILE")).withMinSpacingMillis(0).withConcurrency(2);
      new WebPerfQBitProducer().withQBitConfig(config).produce(instance);
      WebPerfTestSupport.applyChangelog(instance, config.getTableNamePrefix());
      WebPerfTestSupport.login(instance, "web_performance.read", "web_performance.write", "web_performance.manage.hasAccess",
         "web_performance.view.hasAccess", "web_performance.admin.hasAccess", "web_performance.test.hasAccess");
      store = new WebPerfRuntime(config).store(instance);
      evaluator = new WebPerfEvaluationService(store);
      store.update("UPDATE {budget} SET enabled = FALSE");
      WebPerfService service = new WebPerfRuntime(config).service();
      service.applySync(service.planSync(List.of("https://www.example.com/")), START);
      target = WebPerfStore.asLong(store.query("SELECT id FROM {target}").get(0).get("id"));
      store.update("UPDATE {target} SET tier = 'KEY', content_table = 'page', content_id = 7 WHERE id = ?", target);
      RecordingSink.signals.clear();
      RecordingSink.resolutions.clear();
      AlertsInstanceMetaData.ofOrWithNew(instance).withSink(new QCodeReference(RecordingSink.class));
   }



   /*******************************************************************************
    ** Release this test's context.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** Suspect, two confirmations, one alert, then three recovery samples.
    *******************************************************************************/
   @Test
   void confirmsAndRecoversWithoutDuplicates() throws Exception
   {
      baseline();
      long change = change("FRONTEND_DEPLOY", "FRONTEND", "build-2", START.plusSeconds(6));
      long first = measurement(70, "SCHEDULE", "13.1", Map.of("change_event_id", change));
      evaluator.evaluateLab(first);
      assertThat(regression().get("status")).isEqualTo("SUSPECTED");
      assertThat(store.query("SELECT * FROM {lab_run} WHERE trigger_type = 'CONFIRM'")).hasSize(2);
      assertThat(RecordingSink.signals).isEmpty();
      assertThat(openCount()).isEqualTo(1);
      completeConfirmations(first, 72, 71);
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
      assertThat(WebPerfStore.asDouble(regression().get("current_value"))).isEqualTo(71);
      assertThat(regression().get("baseline_ref")).isEqualTo("5,4,3,2,1");
      assertThat(WebPerfStore.asLong(regression().get("cause_change_event_id"))).isEqualTo(change);
      evaluator.evaluateLab(first);
      assertThat(store.query("SELECT * FROM {regression}")).hasSize(1);
      assertThat(RecordingSink.signals).hasSize(1);
      evaluate(96);
      evaluate(95);
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
      evaluate(94);
      assertThat(regression().get("status")).isEqualTo("RESOLVED");
      assertThat(openCount()).isZero();
      assertThat(RecordingSink.resolutions).hasSize(1);
      assertThat(RecordingSink.signals.get(0).getDedupKey()).isEqualTo(RecordingSink.resolutions.get(0).getDedupKey());
   }



   /*******************************************************************************
    ** Lab noise stays visible as not confirmed, with no alert.
    *******************************************************************************/
   @Test
   void rejectsNoise() throws Exception
   {
      baseline();
      long first = evaluate(70);
      completeConfirmations(first, 96, 95);
      assertThat(regression().get("status")).isEqualTo("NOT_CONFIRMED");
      assertThat(openCount()).isZero();
      assertThat(RecordingSink.signals).isEmpty();
   }



   /*******************************************************************************
    ** Baselines require five eligible recent results on the same major version.
    *******************************************************************************/
   @Test
   void boundsBaselineHistory() throws Exception
   {
      for(int i = 0; i < 4; i++)
      {
         measurement(96, "SCHEDULE", "13.0", Map.of());
      }
      measurement(96, "MANUAL", "13.0", Map.of());
      measurement(96, "SCHEDULE", "12.9", Map.of());
      long old = measurement(96, "SCHEDULE", "13.0", Map.of());
      store.update("UPDATE {lab_run} SET completed_at = ? WHERE id = ?", START.minusSeconds(31 * 86400L), old);
      evaluate(70);
      assertThat(store.query("SELECT * FROM {regression}")).isEmpty();
      measurement(96, "SITE_AUDIT", "13.1", Map.of());
      evaluate(60);
      assertThat(regression().get("status")).isEqualTo("SUSPECTED");
   }



   /*******************************************************************************
    ** A major-version change records a marker and retires incompatible baselines.
    *******************************************************************************/
   @Test
   void resetsAtMajorVersionChange() throws Exception
   {
      baseline();
      long first = evaluate(70);
      completeConfirmations(first, 71, 72);
      evaluator.evaluateLab(measurement(60, "SCHEDULE", "14.0", Map.of()));
      assertThat(regression().get("status")).isEqualTo("RESOLVED");
      assertThat(regression().get("resolution_note")).isEqualTo("Lighthouse major version changed; baseline reset");
      assertThat(store.query("SELECT * FROM {change_event} WHERE kind = 'LIGHTHOUSE_VERSION'")).hasSize(1);
      assertThat(store.query("SELECT * FROM {regression}")).hasSize(1);
   }



   /*******************************************************************************
    ** Relative and absolute thresholds; deterministic categories confirm now.
    *******************************************************************************/
   @Test
   void appliesMetricThresholds() throws Exception
   {
      baseline();
      evaluator.evaluateLab(measurement(95, "MANUAL", "13.0", Map.of("lcp_ms", 2499, "cls", 0.049, "tbt_ms", 199)));
      assertThat(store.query("SELECT * FROM {regression}")).isEmpty();
      evaluator.evaluateLab(measurement(95, "MANUAL", "13.0", Map.of("lcp_ms", 2500, "cls", 0.05, "tbt_ms", 200,
         "score_accessibility", 95, "score_best_practices", 95, "score_seo", 95)));
      assertThat(store.query("SELECT * FROM {regression} WHERE status = 'SUSPECTED'")).hasSize(3);
      assertThat(store.query("SELECT * FROM {regression} WHERE status = 'CONFIRMED'")).hasSize(3);
      assertThat(store.query("SELECT * FROM {lab_run} WHERE trigger_type = 'CONFIRM'")).hasSize(2);
      assertThat(RecordingSink.signals).hasSize(3);
   }



   /*******************************************************************************
    ** A newly failing audit is actionable even if the rounded score is unchanged.
    *******************************************************************************/
   @Test
   void newlyFailingAuditConfirms() throws Exception
   {
      baseline();
      long run = measurement(96, "MANUAL", "13.0", Map.of());
      store.insert(WebPerfSchema.LAB_FINDING, Map.of("run_id", run, "target_id", target, "strategy", "MOBILE",
         "category", "SEO", "audit_id", "robots-txt", "kind", "FAILED_AUDIT", "score", 0));
      evaluator.evaluateLab(run);
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
      assertThat(regression().get("failed_audits")).isEqualTo("robots-txt");
      evaluate(96);
      evaluate(96);
      evaluate(96);
      assertThat(regression().get("status")).isEqualTo("RESOLVED");
   }



   /*******************************************************************************
    ** Every scope and comparator, with source, strategy and severity matching.
    *******************************************************************************/
   @Test
   void matchesBudgetScopesComparatorsAndSeverity() throws Exception
   {
      Map<String, Object> page = store.query("SELECT * FROM {target}").get(0);
      for(String scope : List.of("SITE", "TIER", "TARGET", "CONTENT_TABLE", "PATH_PREFIX"))
      {
         String value = switch(scope)
         {
            case "SITE" -> "host:www.example.com";
            case "TIER" -> "KEY";
            case "TARGET" -> String.valueOf(target);
            case "CONTENT_TABLE" -> "page";
            default -> "/";
         };
         long id = budget(scope, value, "LAB", "MOBILE", "LCP", "<=", 2500, "WARNING");
         assertThat(evaluator.breachedBudget(page, "LAB", "MOBILE", "LCP", 2500)).isNull();
         assertThat(evaluator.breachedBudget(page, "LAB", "MOBILE", "LCP", 2501)).isNotNull();
         assertThat(evaluator.breachedBudget(page, "LAB", "DESKTOP", "LCP", 2501)).isNull();
         assertThat(evaluator.breachedBudget(page, "CRUX", "MOBILE", "LCP", 2501)).isNull();
         store.update("UPDATE {budget} SET scope_value = 'unmatched' WHERE id = ?", id);
         assertThat(evaluator.breachedBudget(page, "LAB", "MOBILE", "LCP", 2501)).isNull();
         store.update("UPDATE {budget} SET enabled = FALSE WHERE id = ?", id);
      }
      for(String comparator : List.of("<", ">", ">=", "=", "!=", "<=", "==", "<>", "LE", "GE", "LT", "GT", "EQ", "NE"))
      {
         long id = budget("SITE", "", "LAB", null, "LCP", comparator, 10, "WARNING");
         for(double value : List.of(9.0, 10.0, 11.0))
         {
            boolean acceptable = switch(comparator)
            {
               case "<", "LT" -> value < 10;
               case ">", "GT" -> value > 10;
               case ">=", "GE" -> value >= 10;
               case "<=", "LE" -> value <= 10;
               case "=", "==", "EQ" -> value == 10;
               default -> value != 10;
            };
            assertThat(evaluator.breachedBudget(page, "LAB", "MOBILE", "LCP", value) == null).isEqualTo(acceptable);
         }
         store.update("UPDATE {budget} SET enabled = FALSE WHERE id = ?", id);
      }
      budget("TIER", "KEY", "LAB", null, "PERFORMANCE_SCORE", ">=", 90, "WARNING");
      long critical = budget("TIER", "KEY", "LAB", null, "PERFORMANCE_SCORE", ">=", 50, "CRITICAL");
      long first = evaluate(40);
      assertThat(WebPerfStore.asLong(regression().get("budget_id"))).isEqualTo(critical);
      completeConfirmations(first, 40, 41);
      assertThat(regression().get("severity")).isEqualTo("CRITICAL");
   }



   /*******************************************************************************
    ** CrUX URL budgets use real values, never absent data or origin fallbacks.
    *******************************************************************************/
   @Test
   void evaluatesCruxUrlBudgets() throws Exception
   {
      budget("SITE", "", "CRUX", "MOBILE", "LCP", "<=", 2500, "WARNING");
      long first = field("URL", "OK", 3000, 1);
      evaluator.evaluateField(first);
      evaluator.evaluateField(first);
      assertThat(regression().get("source")).isEqualTo("CRUX");
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
      evaluator.evaluateField(field("URL", "NO_DATA", null, 2));
      evaluator.evaluateField(field("ORIGIN", "OK", 1000, 3));
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
      evaluator.evaluateField(field("URL", "OK", 2000, 4));
      assertThat(regression().get("status")).isEqualTo("RESOLVED");
      assertThat(openCount()).isZero();
      assertThat(RecordingSink.signals).hasSize(1);
   }



   /*******************************************************************************
    ** Poll replay, settle delays and attribution through the real queue worker.
    *******************************************************************************/
   @Test
   void pollsAndAttributesWorkerResults() throws Exception
   {
      MutableProbe probe = new MutableProbe();
      config.withDeployProbe(probe).withLabRunner(new FakeRunner());
      ChangeEventSource.Change cdn = new ChangeEventSource.Change("CDN_STAGE", "www.example.com", "OFF", "HEADERS_ONLY", START);
      ChangeEventSource.Change content = new ChangeEventSource.Change("CONTENT", "page:7", "old", "new", START.plusSeconds(1));
      config.withChangeEventSource(since -> List.of(cdn, content));
      WebPerfChangeService changes = new WebPerfChangeService(store);
      changes.poll(START.plusSeconds(2));
      changes.poll(START.plusSeconds(3));
      assertThat(store.query("SELECT * FROM {change_event}")).hasSize(3);
      assertThat(store.query("SELECT * FROM {lab_run} WHERE trigger_type = 'CHANGE_COMPARE'")).hasSize(3);
      assertThat(store.query("SELECT * FROM {lab_run} WHERE trigger_type = 'DEPLOY'").get(0).get("not_before")).isEqualTo(START.plusSeconds(302));
      new WebPerfRuntime(config).service().runDue(Instant.now());
      List<Map<String, Object>> done = store.query("SELECT * FROM {lab_run} WHERE status = 'SUCCEEDED'");
      assertThat(done).hasSize(2);
      assertThat(done).allSatisfy(row ->
      {
         assertThat(row.get("deployment_id")).isEqualTo("build-1");
         assertThat(row.get("cdn_stage")).isEqualTo("HEADERS_ONLY");
         assertThat(row.get("evaluated_at")).isNotNull();
      });
      probe.build = "build-2";
      changes.poll(Instant.now());
      assertThat(store.query("SELECT * FROM {change_event} WHERE kind = 'FRONTEND_DEPLOY'")).hasSize(2);
      probe.build = null;
      changes.poll(Instant.now());
      assertThat(store.query("SELECT * FROM {change_event} WHERE kind = 'FRONTEND_DEPLOY'")).hasSize(2);
   }



   /*******************************************************************************
    ** Three samples on each side create reproducible, idempotent comparisons.
    *******************************************************************************/
   @Test
   void recordsComparisonsWithCountsAndWindows() throws Exception
   {
      for(int i = 0; i < 3; i++)
      {
         measurement(90, "CHANGE_COMPARE", "13.0", Map.of("lcp_ms", 3000, "fcp_ms", 2000, "ttfb_ms", 900));
      }
      long event = change("CDN_STAGE", "www.example.com", "LIVE", START.plusSeconds(4));
      sequence = 4;
      for(int i = 0; i < 3; i++)
      {
         long id = measurement(96, "CHANGE_COMPARE", "13.1", Map.of("change_event_id", event, "lcp_ms", 2000, "fcp_ms", 1500, "ttfb_ms", 600));
         evaluator.evaluateLab(id);
         assertThat(store.query("SELECT * FROM {comparison}")).hasSize(i == 2 ? 4 : 0);
      }
      assertThat(new WebPerfComparisonService(store).compare(event)).isEqualTo(4);
      assertThat(store.query("SELECT * FROM {comparison}")).hasSize(4);
      Map<String, Object> lcp = store.query("SELECT * FROM {comparison} WHERE metric = 'LCP'").get(0);
      assertThat(WebPerfStore.asDouble(lcp.get("delta"))).isEqualTo(-1000);
      assertThat(WebPerfStore.asInt(lcp.get("before_n"))).isEqualTo(3);
      assertThat(WebPerfStore.asInt(lcp.get("after_n"))).isEqualTo(3);
      assertThat(lcp.get("before_window").toString()).contains(START.plusSeconds(1).toString());
      assertThat(runAction(WebPerfProcesses.COMPARE_CHANGE, Map.of()).getException()).isEmpty();
   }



   /*******************************************************************************
    ** Real ETL actions require a note and manage permission, and retain history.
    *******************************************************************************/
   @Test
   void actionsRequireNotesAndPermissions() throws Exception
   {
      budget("SITE", "", "LAB", null, "SEO_SCORE", ">=", 100, "WARNING");
      evaluator.evaluateLab(measurement(96, "MANUAL", "13.0", Map.of("score_seo", 90)));
      long id = WebPerfStore.asLong(regression().get("id"));
      assertThrows(com.kingsrook.qqq.backend.core.exceptions.QException.class, () -> runAction(WebPerfProcesses.DISMISS_REGRESSIONS, Map.of()));
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
      WebPerfTestSupport.login(instance, "web_performance.read", "web_performance.write");
      assertThrows(com.kingsrook.qqq.backend.core.exceptions.QException.class, () -> runAction(WebPerfProcesses.DISMISS_REGRESSIONS, Map.of(RegressionSteps.NOTE, "Expected change")));
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
      WebPerfTestSupport.login(instance, "web_performance.read", "web_performance.write", "web_performance.manage.hasAccess");
      RunProcessOutput result = runAction(WebPerfProcesses.DISMISS_REGRESSIONS, Map.of(RegressionSteps.NOTE, "Expected change"));
      assertThat(result.getException()).isEmpty();
      assertThat(result.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY)).isNotNull();
      assertThat(regression().get("status")).isEqualTo("DISMISSED");
      assertThat(regression().get("resolution_note")).isEqualTo("Expected change");
      assertThat(openCount()).isZero();
      assertThat(evaluator.finishRegression(id, "RESOLVED", "Already closed", START)).isFalse();
      evaluator.evaluateLab(measurement(96, "MANUAL", "13.0", Map.of("score_seo", 90)));
      assertThat(runAction(WebPerfProcesses.RESOLVE_REGRESSIONS, Map.of(RegressionSteps.NOTE, "Fixed externally")).getException()).isEmpty();
      assertThat(store.query("SELECT * FROM {regression} WHERE status = 'RESOLVED'")).hasSize(1);
   }



   /*******************************************************************************
    ** Target locking prevents concurrent duplicate incidents; failed writes roll back.
    *******************************************************************************/
   @Test
   void serializesEvaluationAndRollsBack() throws Exception
   {
      baseline();
      long id = measurement(70, "SCHEDULE", "13.0", Map.of());
      try(var executor = Executors.newFixedThreadPool(2))
      {
         var first = executor.submit(() ->
         {
            evaluator.evaluateLab(id);
            return (null);
         });
         var second = executor.submit(() ->
         {
            evaluator.evaluateLab(id);
            return (null);
         });
         first.get();
         second.get();
      }
      assertThat(store.query("SELECT * FROM {regression}")).hasSize(1);
      assertThat(store.query("SELECT * FROM {lab_run} WHERE trigger_type = 'CONFIRM'")).hasSize(2);
      assertThrows(java.sql.SQLException.class, () -> store.transaction(tx ->
      {
         tx.update("UPDATE {target} SET label = 'must roll back' WHERE id = ?", target);
         tx.update("UPDATE no_such_table SET id = 1");
         return (null);
      }));
      assertThat(store.query("SELECT label FROM {target}").get(0).get("label")).isNotEqualTo("must roll back");
   }



   /*******************************************************************************
    ** Core falls back to logging without an Alerts QBit; feature gating works.
    *******************************************************************************/
   @Test
   void alertsAreOptional() throws Exception
   {
      AlertsInstanceMetaData.ofOrWithNew(instance).withSink(null);
      long failures = QAlerts.getProducerFailureCount();
      budget("SITE", "", "LAB", null, "SEO_SCORE", ">=", 100, "WARNING");
      evaluator.evaluateLab(measurement(96, "MANUAL", "13.0", Map.of("score_seo", 90)));
      assertThat(QAlerts.getProducerFailureCount()).isEqualTo(failures);
      config.getFeatures().remove(WebPerfFeature.ALERTS);
      evaluator.finishRegression(WebPerfStore.asLong(regression().get("id")), "RESOLVED", "Done", START);
      assertThat(RecordingSink.signals).isEmpty();
   }



   /*******************************************************************************
    ** TTFB comes from a measured subpart and is null when not reported.
    *******************************************************************************/
   @Test
   void parsesMeasuredTtfbOnly()
   {
      assertThat(LabResultParser.ttfb("{\"audits\":{}}")).isNull();
      assertThat(LabResultParser.ttfb("{\"audits\":{\"lcp-phases-insight\":{\"details\":{\"items\":[{\"subpart\":\"timeToFirstByte\",\"duration\":321.4}]}}}}")).isEqualTo(321);
   }



   /*******************************************************************************
    ** Quota skips remain visible, and retry preserves each deliberate sample slot.
    *******************************************************************************/
   @Test
   void retriesCappedConfirmationSamples() throws Exception
   {
      baseline();
      config.withMaxRunsPerDay(1);
      long first = evaluate(70);
      assertThat(store.query("SELECT * FROM {lab_run} WHERE status = 'SKIPPED_QUOTA'")).hasSize(1);
      config.withMaxRunsPerDay(500);
      assertThat(runAction(WebPerfProcesses.RETRY_RUNS, Map.of()).getException()).isEmpty();
      assertThat(store.query("SELECT * FROM {lab_run} WHERE status = 'QUEUED' AND trigger_type = 'CONFIRM'")).hasSize(2);
      assertThat(runAction(WebPerfProcesses.RETRY_RUNS, Map.of()).getException()).isEmpty();
      assertThat(store.query("SELECT * FROM {lab_run} WHERE status = 'QUEUED' AND trigger_type = 'CONFIRM'")).hasSize(2);
      store.update("UPDATE {lab_run} SET status = 'SUCCEEDED', completed_at = ?, lighthouse_version = '13.0', score_performance = 70 WHERE status = 'QUEUED'",
         START.plusSeconds(10));
      evaluator.evaluatePending();
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
      assertThat(WebPerfStore.asLong(regression().get("first_run_id"))).isEqualTo(first);
   }



   /*******************************************************************************
    ** Existing seeded budgets are evaluated even before enough baseline exists.
    *******************************************************************************/
   @Test
   void seededBudgetsWorkWithoutBaseline() throws Exception
   {
      store.update("UPDATE {budget} SET enabled = TRUE WHERE seeded = TRUE");
      long first = evaluate(40);
      assertThat(regression().get("status")).isEqualTo("SUSPECTED");
      assertThat(regression().get("baseline_value")).isNull();
      assertThat(regression().get("severity")).isEqualTo("CRITICAL");
      completeConfirmations(first, 40, 40);
      assertThat(regression().get("status")).isEqualTo("CONFIRMED");
   }



   /*******************************************************************************
    ** Build replays are idempotent even after a rollback to an earlier build.
    *******************************************************************************/
   @Test
   void attributesRollbackAndQueuesBothCdnWindows() throws Exception
   {
      MutableProbe probe = new MutableProbe();
      config.withDeployProbe(probe);
      WebPerfChangeService changes = new WebPerfChangeService(store);
      changes.poll(START);
      probe.build = "build-2";
      changes.poll(START.plusSeconds(1));
      probe.build = "build-1";
      changes.poll(START.plusSeconds(2));
      long run = measurement(96, "MANUAL", "13.0", Map.of());
      changes.attribute(run, START.plusSeconds(3));
      assertThat(store.query("SELECT deployment_id FROM {lab_run} WHERE id = ?", run).get(0).get("deployment_id")).isEqualTo("build-1");
      assertThat(store.query("SELECT * FROM {change_event}")).hasSize(2);
      ChangeEventSource.Change cdn = new ChangeEventSource.Change("CDN_STAGE", "www.example.com", "HEADERS_ONLY", "LIVE", START.plusSeconds(4));
      config.withChangeEventSource(since -> List.of(cdn));
      changes.poll(START.plusSeconds(4));
      changes.poll(START.plusSeconds(5));
      assertThat(store.query("SELECT * FROM {lab_run} WHERE trigger_type = 'CHANGE_COMPARE'")).hasSize(6);
      assertThat(store.query("SELECT * FROM {lab_run} WHERE trigger_type = 'CHANGE_COMPARE' AND not_before = ?", START.plusSeconds(3604))).hasSize(3);
      assertThat(store.query("SELECT * FROM {lab_run} WHERE trigger_type = 'CHANGE_COMPARE' AND not_before = ?", START.plusSeconds(86404))).hasSize(3);
      assertThat(runAction(WebPerfProcesses.PROBE_DEPLOYS, Map.of()).getException()).isEmpty();
   }



   /*******************************************************************************
    ** Five clean baseline measurements.
    *******************************************************************************/
   private void baseline() throws Exception
   {
      for(int i = 0; i < 5; i++)
      {
         measurement(96, "SCHEDULE", "13.0", Map.of());
      }
   }



   /*******************************************************************************
    ** Store a deterministic successful measurement, with no network access.
    *******************************************************************************/
   private long measurement(int score, String trigger, String version, Map<String, Object> extra) throws Exception
   {
      Instant time = START.plusSeconds(++sequence);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("target_id", target);
      row.put("url", "https://www.example.com/");
      row.put("strategy", "MOBILE");
      row.put("status", "SUCCEEDED");
      row.put("trigger_type", trigger);
      row.put("lighthouse_version", version);
      row.put("started_at", time);
      row.put("completed_at", time);
      row.put("queued_at", time);
      row.put("score_performance", score);
      row.put("score_accessibility", 100);
      row.put("score_best_practices", 100);
      row.put("score_seo", 100);
      row.put("lcp_ms", 2000);
      row.put("fcp_ms", 1000);
      row.put("tbt_ms", 0);
      row.put("cls", 0);
      row.putAll(extra);
      return (store.insert(WebPerfSchema.LAB_RUN, row));
   }



   /*******************************************************************************
    ** Store and evaluate the next performance measurement.
    *******************************************************************************/
   private long evaluate(int score) throws Exception
   {
      long id = measurement(score, "SCHEDULE", "13.0", Map.of());
      evaluator.evaluateLab(id);
      return (id);
   }



   /*******************************************************************************
    ** Complete the independently queued confirmation samples.
    *******************************************************************************/
   private void completeConfirmations(long first, int score1, int score2) throws Exception
   {
      int index = 0;
      for(Map<String, Object> row : store.query("SELECT id FROM {lab_run} WHERE trigger_ref IN (?, ?) ORDER BY id", "confirm:" + first + ":0", "confirm:" + first + ":1"))
      {
         store.update("UPDATE {lab_run} SET status = 'SUCCEEDED', completed_at = ?, lighthouse_version = '13.0', score_performance = ? WHERE id = ?",
            START.plusSeconds(++sequence), index++ == 0 ? score1 : score2, row.get("id"));
         evaluator.evaluateLab(WebPerfStore.asLong(row.get("id")));
      }
   }



   /*******************************************************************************
    ** One editable budget.
    *******************************************************************************/
   private long budget(String scope, String scopeValue, String source, String strategy, String metric, String comparator, double threshold, String severity) throws Exception
   {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", "Test budget");
      row.put("scope", scope);
      row.put("scope_value", scopeValue);
      row.put("source", source);
      row.put("strategy", strategy);
      row.put("metric", metric);
      row.put("comparator", comparator);
      row.put("threshold", threshold);
      row.put("severity", severity);
      row.put("enabled", true);
      return (store.insert(WebPerfSchema.BUDGET, row));
   }



   /*******************************************************************************
    ** A source event without automatic queueing for fixtures.
    *******************************************************************************/
   private long change(String kind, String component, String value, Instant time) throws Exception
   {
      return (new WebPerfChangeService(store).record(new ChangeEventSource.Change(kind, component, "before", value, time), "WEBHOOK", "test", false));
   }



   /*******************************************************************************
    ** A real or unavailable CrUX collection period.
    *******************************************************************************/
   private long field(String scope, String status, Integer lcp, int day) throws Exception
   {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("source", "CRUX_API");
      row.put("scope", scope);
      row.put("scope_key", "https://www.example.com/");
      row.put("form_factor", "PHONE");
      row.put("status", status);
      row.put("p75_lcp_ms", lcp);
      row.put("period_end", LocalDate.of(2026, 10, day));
      row.put("last_checked_at", START.plusSeconds(day));
      return (store.insert(WebPerfSchema.FIELD_SNAPSHOT, row));
   }



   /*******************************************************************************
    ** The first incident, retained through status changes.
    *******************************************************************************/
   private Map<String, Object> regression() throws Exception
   {
      return (store.query("SELECT * FROM {regression} ORDER BY id").get(0));
   }



   /*******************************************************************************
    ** The count shown in Tested Pages.
    *******************************************************************************/
   private int openCount() throws Exception
   {
      return (WebPerfStore.asInt(store.query("SELECT open_regression_count FROM {target} WHERE id = ?", target).get(0).get("open_regression_count")));
   }



   /*******************************************************************************
    ** Run the actual ETL process, following the existing frontend-skip convention.
    *******************************************************************************/
   private RunProcessOutput runAction(String name, Map<String, Serializable> values) throws Exception
   {
      RunProcessInput input = new RunProcessInput();
      input.setPermissionEnforcement(com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement.ENFORCE);
      input.setProcessName(config.qualify(name));
      input.setValues(new HashMap<>(values));
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      input.setCallback(new QProcessCallback()
      {
         /*******************************************************************************
          ** Select this test's records.
          *******************************************************************************/
         @Override
         public QQueryFilter getQueryFilter()
         {
            return (new QQueryFilter());
         }
      });
      return (new RunProcessAction().execute(input));
   }



   /*******************************************************************************
    ** Core alert sink, without any Alerts QBit dependency.
    *******************************************************************************/
   public static class RecordingSink implements AlertSinkInterface
   {
      static final List<AlertSignal> signals = new ArrayList<>();
      static final List<AlertResolution> resolutions = new ArrayList<>();



      /*******************************************************************************
       ** Capture a raise.
       *******************************************************************************/
      @Override
      public void raise(AlertSignal signal)
      {
         signals.add(signal);
      }



      /*******************************************************************************
       ** Capture a resolution.
       *******************************************************************************/
      @Override
      public void resolve(AlertResolution resolution)
      {
         resolutions.add(resolution);
      }



      /*******************************************************************************
       ** This producer does not emit heartbeats.
       *******************************************************************************/
      @Override
      public void heartbeat(String key, Instant at)
      {
      }
   }



   /*******************************************************************************
    ** A probe controlled by the test.
    *******************************************************************************/
   private static class MutableProbe implements DeployProbe
   {
      String build = "build-1";



      /*******************************************************************************
       ** Frontend component.
       *******************************************************************************/
      @Override
      public String component()
      {
         return ("FRONTEND");
      }



      /*******************************************************************************
       ** Build id without transport.
       *******************************************************************************/
      @Override
      public String currentBuildId()
      {
         return (build);
      }
   }



   /*******************************************************************************
    ** A minimal Lighthouse runner without transport.
    *******************************************************************************/
   private static class FakeRunner implements LabRunner
   {
      /*******************************************************************************
       ** Stored runner name.
       *******************************************************************************/
      @Override
      public String name()
      {
         return ("TEST");
      }



      /*******************************************************************************
       ** Return a successful response.
       *******************************************************************************/
      @Override
      public LabResponse run(LabRequest request)
      {
         JSONObject body = new JSONObject().put("lighthouseVersion", "13.0").put("finalUrl", request.url())
            .put("categories", new JSONObject().put("performance", new JSONObject().put("score", 0.96)))
            .put("audits", new JSONObject());
         return (new LabResponse(200, body.toString(), Instant.now()));
      }
   }
}
