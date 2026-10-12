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


import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import com.kingsrook.qbits.webperf.field.CruxClient;
import com.kingsrook.qbits.webperf.lab.PsiLabRunner;
import com.kingsrook.qbits.webperf.processes.WebPerfProcesses;
import com.kingsrook.qbits.webperf.service.WebPerfService;
import com.kingsrook.qbits.webperf.spi.ContentResolver;
import com.kingsrook.qbits.webperf.spi.UrlSource;
import com.kingsrook.qbits.webperf.store.WebPerfStore;
import com.kingsrook.qbits.webperf.widgets.WebPerfWidgets;
import com.kingsrook.qqq.backend.core.actions.dashboard.RenderWidgetAction;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.ChartData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Phase P1 end to end on H2 with the real changelog and a local server
 ** standing in for Google: sitemap sync, the queue (merge, daily cap), the
 ** worker (success, retry with backoff, failure, no key), Google field data
 ** (NO_DATA kept honestly), every action through QQQ's RunProcessAction, and
 ** every widget through RenderWidgetAction, with and without data.
 *******************************************************************************/
class WebPerfP1Test
{
   static final String SITE = "https://www.example.com";

   private QInstance         qInstance;
   private WebPerfQBitConfig config;
   private FixtureServer     google;
   private List<String>      sitemap;



   /*******************************************************************************
    ** An instance with a content table "page", the QBit wired to it, and the
    ** local Google stand-in.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      google = new FixtureServer();
      sitemap = new ArrayList<>(List.of(SITE + "/", SITE + "/about", SITE + "/blog/hello"));
      List<String> source = sitemap;

      qInstance = WebPerfTestSupport.newInstance();
      qInstance.addTable(new QTableMetaData().withName("page").withBackendName(WebPerfTestSupport.BACKEND).withPrimaryKeyField("id")
         .withBackendDetails(new RDBMSTableBackendDetails().withTableName("page"))
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("slug", QFieldType.STRING))
         .withSection(new com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection("main", new com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon("description"),
            com.kingsrook.qqq.backend.core.model.metadata.tables.Tier.T1, List.of("id", "slug"))));

      config = WebPerfTestSupport.config()
         .withSiteBaseUrl(SITE)
         .withContentTables(List.of("page"))
         .withKeyPaths(List.of("/"))
         .withMinSpacingMillis(0)
         .withCruxSpacingMillis(0)
         .withUrlSource((UrlSource) () -> new ArrayList<>(source))
         .withContentResolver((ContentResolver) path -> "/about".equals(path) ? new ContentResolver.ContentRef("page", 1L) : null)
         .withLabRunner(new PsiLabRunner(google.url("/runPagespeed"), "AIzaTestKey"))
         .withCruxClient(new CruxClient(google.url("/queryRecord"), "AIzaTestKey"));
      new WebPerfQBitProducer().withQBitConfig(config).produce(qInstance);
      new QInstanceValidator().validate(qInstance);
      WebPerfTestSupport.applyChangelog(qInstance, "webperf_");
      try(Connection connection = ConnectionManager.getConnection((RDBMSBackendMetaData) qInstance.getBackend(WebPerfTestSupport.BACKEND));
         Statement statement = connection.createStatement())
      {
         statement.execute("CREATE TABLE page (id INTEGER PRIMARY KEY, slug VARCHAR(100))");
         statement.execute("INSERT INTO page (id, slug) VALUES (1, 'about'), (2, 'draft')");
      }
      WebPerfTestSupport.login(qInstance, "webperf.read", "webperf.write", "webperf.test.hasAccess", "webperf.admin.hasAccess", "webperf.view.hasAccess");
   }



   /*******************************************************************************
    ** Stop the stand-in.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      google.close();
      QContext.clear();
   }



   /*******************************************************************************
    ** The service and store.
    *******************************************************************************/
   private WebPerfService service()
   {
      return (WebPerfRuntime.of(qInstance, "webperf_").service());
   }



   private WebPerfStore store()
   {
      return (service().getStore());
   }



   /*******************************************************************************
    ** Sync adds pages (KEY by path, linked to records), and marks pages that
    ** left the sitemap without deleting them.
    *******************************************************************************/
   @Test
   void testSync() throws Exception
   {
      WebPerfService service = service();
      service.applySync(service.planSync(sitemap), Instant.now());
      List<Map<String, Object>> rows = store().query("SELECT url, tier, content_table, content_id, in_sitemap FROM {target} ORDER BY id");
      assertEquals(3, rows.size());
      assertEquals("KEY", rows.get(0).get("tier"));
      assertEquals("STANDARD", rows.get(1).get("tier"));
      assertEquals("page", rows.get(1).get("content_table"));
      assertEquals(1L, WebPerfStore.asLong(rows.get(1).get("content_id")));

      WebPerfService.SyncPlan again = service.planSync(List.of(SITE + "/", SITE + "/about"));
      assertEquals(0, again.newUrls().size());
      assertEquals(2, again.seenIds().size());
      assertEquals(1, again.removedIds().size());
      service.applySync(again, Instant.now());
      assertEquals(3, store().query("SELECT id FROM {target}").size(), "nothing is deleted");
      assertEquals(Boolean.FALSE, store().query("SELECT in_sitemap FROM {target} WHERE url = ?", SITE + "/blog/hello").get(0).get("in_sitemap"));
   }



   /*******************************************************************************
    ** Queueing merges into waiting runs and records runs past the cap.
    *******************************************************************************/
   @Test
   void testQueueMergeAndCap() throws Exception
   {
      config.withMaxRunsPerDay(3);
      WebPerfService service = service();
      service.applySync(service.planSync(sitemap), Instant.now());
      WebPerfService.QueuePlan plan = service.enqueue(service.requestsFor(service.enabledTargets(false), "MANUAL", "test", Instant.now()), Instant.now());
      assertEquals(3, plan.queue().size());
      assertEquals(3, plan.overQuota().size());
      assertEquals(3, store().query("SELECT id FROM {lab_run} WHERE status = 'QUEUED'").size());
      assertEquals(3, store().query("SELECT id FROM {lab_run} WHERE status = 'SKIPPED_QUOTA'").size());

      WebPerfService.QueuePlan again = service.enqueue(service.requestsFor(service.enabledTargets(true), "MANUAL", "test", Instant.now()), Instant.now());
      assertEquals(2, again.merged().size(), "the KEY page's mobile and desktop runs are already waiting");
   }



   /*******************************************************************************
    ** The worker runs a test, stores scores, findings, the report and the
    ** page's latest results.
    *******************************************************************************/
   @Test
   void testWorkerSuccess() throws Exception
   {
      google.respond(new FixtureServer.Response(200, FixtureServer.fixture("psi-kof22-home-mobile.json")));
      WebPerfService service = service();
      service.applySync(service.planSync(List.of(SITE + "/")), Instant.now());
      service.enqueue(service.requestsFor(service.enabledTargets(false), "MANUAL", "test", Instant.now()), Instant.now());

      assertEquals(2, service.runDue(Instant.now()));
      List<Map<String, Object>> runs = store().query("SELECT id, status, score_performance, score_seo, lcp_ms, lighthouse_version, attempts FROM {lab_run} ORDER BY id");
      assertThat(runs).allMatch(r -> "SUCCEEDED".equals(r.get("status")) && Integer.valueOf(81).equals(WebPerfStore.asInt(r.get("score_performance"))));
      assertEquals("13.5.0", runs.get(0).get("lighthouse_version"));
      assertEquals(1, WebPerfStore.asInt(runs.get(0).get("attempts")));
      assertThat(store().query("SELECT id FROM {lab_finding} WHERE run_id = ?", runs.get(0).get("id"))).hasSizeGreaterThan(5);

      Map<String, Object> report = store().query("SELECT content, bytes, sha256 FROM {lab_report} WHERE run_id = ?", runs.get(0).get("id")).get(0);
      byte[]              raw;
      try(GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream((byte[]) report.get("content"))))
      {
         raw = in.readAllBytes();
      }
      assertEquals(FixtureServer.fixture("psi-kof22-home-mobile.json"), new String(raw, StandardCharsets.UTF_8), "the full response is kept unchanged");
      assertEquals(raw.length, WebPerfStore.asInt(report.get("bytes")));

      Map<String, Object> target = store().query("SELECT latest_mobile_performance, latest_desktop_performance, latest_accessibility FROM {target}").get(0);
      assertEquals(81, WebPerfStore.asInt(target.get("latest_mobile_performance")));
      assertEquals(81, WebPerfStore.asInt(target.get("latest_desktop_performance")));
      assertEquals(82, WebPerfStore.asInt(target.get("latest_accessibility")));
      assertEquals(0, service.runDue(Instant.now()), "nothing left");
      assertThat(google.getRequests().get(0).query()).contains("key=AIzaTestKey");
   }



   /*******************************************************************************
    ** Server errors retry with backoff then fail; client errors fail at once;
    ** without a runner nothing is claimed.
    *******************************************************************************/
   @Test
   void testWorkerRetriesAndFailures() throws Exception
   {
      config.withStrategies(List.of("MOBILE"));
      WebPerfService service = service();
      service.applySync(service.planSync(List.of(SITE + "/")), Instant.now());
      service.enqueue(service.requestsFor(service.enabledTargets(false), "MANUAL", "test", Instant.now()), Instant.now());

      google.respond(new FixtureServer.Response(500, "{\"error\":{\"message\":\"Backend error\"}}"));
      for(int attempt = 1; attempt <= WebPerfService.MAX_ATTEMPTS; attempt++)
      {
         assertEquals(1, service.runDue(Instant.now().plus(Duration.ofHours(1))));
      }
      Map<String, Object> run = store().query("SELECT status, attempts, http_status, error_message FROM {lab_run}").get(0);
      assertEquals("FAILED", run.get("status"));
      assertEquals(3, WebPerfStore.asInt(run.get("attempts")));
      assertEquals("HTTP 500: Backend error", run.get("error_message"));

      store().update("DELETE FROM {lab_run}");
      service.enqueue(service.requestsFor(service.enabledTargets(false), "MANUAL", "test", Instant.now()), Instant.now());
      google.respond(new FixtureServer.Response(500, "{}"));
      service.runDue(Instant.now());
      Map<String, Object> waiting = store().query("SELECT status, attempts, not_before FROM {lab_run}").get(0);
      assertEquals("QUEUED", waiting.get("status"));
      assertTrue(((Instant) waiting.get("not_before")).isAfter(Instant.now()), "backed off");
      assertEquals(0, service.runDue(Instant.now()), "not due yet");

      store().update("DELETE FROM {lab_run}");
      service.enqueue(service.requestsFor(service.enabledTargets(false), "MANUAL", "test", Instant.now()), Instant.now());
      google.respond(new FixtureServer.Response(400, "{\"error\":{\"message\":\"Invalid URL\"}}"));
      service.runDue(Instant.now());
      assertEquals("HTTP 400: Invalid URL", store().query("SELECT error_message FROM {lab_run}").get(0).get("error_message"));

      config.withLabRunner(null);
      store().update("DELETE FROM {lab_run}");
      service.enqueue(service.requestsFor(service.enabledTargets(false), "MANUAL", "test", Instant.now()), Instant.now());
      assertEquals(0, WebPerfRuntime.of(qInstance, "webperf_").service().runDue(Instant.now()), "no key: runs wait");
      assertEquals("QUEUED", store().query("SELECT status FROM {lab_run}").get(0).get("status"));
   }



   /*******************************************************************************
    ** Google field data: NO_DATA is one row per key and form factor whose last
    ** check moves; data is stored per collection period.
    *******************************************************************************/
   @Test
   void testFieldData() throws Exception
   {
      google.respond(new FixtureServer.Response(404, "{\"error\":{\"code\":404}}"));
      WebPerfService service = service();
      Map<String, Integer> first = service.refreshFieldData(Map.of("ORIGIN", List.of(SITE)), Instant.now());
      assertEquals(Map.of("NO_DATA", 3), first);
      service.refreshFieldData(Map.of("ORIGIN", List.of(SITE)), Instant.now().plusSeconds(60));
      assertEquals(3, store().query("SELECT id FROM {field_snapshot}").size(), "an unchanged NO_DATA result is not stored twice");

      google.respond(new FixtureServer.Response(200, FixtureServer.fixture("crux-origin-phone.json")));
      service.refreshFieldData(Map.of("ORIGIN", List.of(SITE)), Instant.now());
      assertEquals(3, store().query("SELECT id FROM {field_snapshot} WHERE status = 'OK'").size());
      assertEquals(2367, WebPerfStore.asInt(store().query("SELECT p75_lcp_ms FROM {field_snapshot} WHERE status = 'OK'").get(0).get("p75_lcp_ms")));
   }



   /*******************************************************************************
    ** The actions through RunProcessAction: sync, test page speed (pages and a
    ** content record), retry, field data; then the scheduled worker.
    *******************************************************************************/
   @Test
   void testActions() throws Exception
   {
      RunProcessOutput sync = run(WebPerfProcesses.SYNC_TARGETS);
      assertThat(summary(sync)).anyMatch(l -> l.contains("3") && l.contains("added"));
      assertEquals(3, store().query("SELECT id FROM {target}").size());

      RunProcessOutput test = run(WebPerfProcesses.TEST_PAGE_SPEED);
      assertThat(summary(test)).anyMatch(l -> l.contains("6") && l.contains("queued"));
      assertEquals(6, store().query("SELECT id FROM {lab_run} WHERE status = 'QUEUED'").size());

      RunProcessOutput again = run(WebPerfProcesses.TEST_PAGE_SPEED + "Page");
      assertThat(summary(again)).anyMatch(l -> l.contains("merged")).anyMatch(l -> l.contains("no tested page"));

      google.respond(new FixtureServer.Response(200, FixtureServer.fixture("psi-kof22-home-mobile.json")));
      for(int i = 0; i < 3; i++)
      {
         runScheduled(WebPerfProcesses.RUN_QUEUED_TESTS);
      }
      assertEquals(6, store().query("SELECT id FROM {lab_run} WHERE status = 'SUCCEEDED'").size());

      store().update("UPDATE {lab_run} SET status = 'FAILED' WHERE id = (SELECT MIN(id) FROM {lab_run})");
      RunProcessOutput retry = run(WebPerfProcesses.RETRY_RUNS);
      assertThat(summary(retry)).anyMatch(l -> l.contains("1") && l.contains("queued again")).anyMatch(l -> l.contains("not retried"));

      google.respond(new FixtureServer.Response(404, "{}"));
      RunProcessOutput field = run(WebPerfProcesses.REFRESH_FIELD_DATA);
      assertThat(summary(field)).anyMatch(l -> l.contains("NO_DATA"));
      assertEquals(12, store().query("SELECT id FROM {field_snapshot}").size(), "origin and 3 pages, 3 form factors each");

      runScheduled(WebPerfProcesses.SCHEDULE_KEY_TESTS);
      assertTrue(store().query("SELECT id FROM {lab_run} WHERE trigger_type = 'SCHEDULE'").size() >= 1);
   }



   /*******************************************************************************
    ** Every widget renders, empty and with data, with honest wording.
    *******************************************************************************/
   @Test
   void testWidgets() throws Exception
   {
      assertThat(html(WebPerfWidgets.SITE_SCORES, Map.of())).contains("No page has been tested yet");
      assertThat(html(WebPerfWidgets.FIELD_STATUS, Map.of())).contains("not checked yet");
      assertThat(html(WebPerfWidgets.RECORD_PANEL, Map.of("tableName", "page", "id", "2"))).contains("no tested page");

      google.respond(new FixtureServer.Response(200, FixtureServer.fixture("psi-kof22-home-mobile.json")));
      WebPerfService service = service();
      service.applySync(service.planSync(sitemap), Instant.now());
      service.enqueue(service.requestsFor(service.enabledTargets(false), "MANUAL", "test", Instant.now()), Instant.now());
      while(service.runDue(Instant.now()) > 0)
      {
         // run everything
      }
      google.respond(new FixtureServer.Response(404, "{}"));
      service.refreshFieldData(Map.of("ORIGIN", List.of(service.origin())), Instant.now());

      assertThat(html(WebPerfWidgets.SITE_SCORES, Map.of())).contains("Performance").contains("<b>81</b>").contains("Lighthouse 13.5.0").contains("key page");
      assertThat(html(WebPerfWidgets.SLOWEST_PAGES, Map.of())).contains("/about").contains("2.27 s");
      assertThat(html(WebPerfWidgets.OPPORTUNITIES, Map.of())).contains("unused-javascript");
      assertThat(html(WebPerfWidgets.RECENT_RUNS, Map.of())).contains("SUCCEEDED").contains("0 runs waiting");
      assertThat(html(WebPerfWidgets.FIELD_STATUS, Map.of())).contains("not available").contains("enough Chrome traffic");
      assertThat(html(WebPerfWidgets.RECORD_PANEL, Map.of("tableName", "page", "id", "1"))).contains("What to fix").contains("<b>81</b>")
         .doesNotContain("<script");

      ChartData chart = (ChartData) new RenderWidgetAction().execute(new RenderWidgetInput()
         .withWidgetMetaData(qInstance.getWidget(config.qualify(WebPerfWidgets.TREND)))).getWidgetData();
      assertNotNull(chart.getChartData());
      assertEquals(1, chart.getChartData().getLabels().size(), "one tested week");
   }



   /*******************************************************************************
    ** Run an action with every frontend step skipped.
    *******************************************************************************/
   private RunProcessOutput run(String name) throws Exception
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(config.qualify(name));
      input.setValues(new HashMap<>(Map.of()));
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      input.setCallback(new com.kingsrook.qqq.backend.core.actions.processes.QProcessCallback()
      {
         @Override
         public com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter getQueryFilter()
         {
            return (new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter());
         }
      });
      RunProcessOutput output = new RunProcessAction().execute(input);
      assertTrue(output.getException().isEmpty(), () -> String.valueOf(output.getException().orElse(null)));
      return (output);
   }



   /*******************************************************************************
    ** Run a scheduled (hidden) process.
    *******************************************************************************/
   private void runScheduled(String name) throws Exception
   {
      run(name);
   }



   /*******************************************************************************
    ** The Result summary lines as text.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private static List<String> summary(RunProcessOutput output)
   {
      Serializable  value = output.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      List<String> lines = new ArrayList<>();
      for(ProcessSummaryLineInterface line : (List<ProcessSummaryLineInterface>) value)
      {
         if(line instanceof com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine summaryLine)
         {
            lines.add(summaryLine.getCount() + " " + summaryLine.getMessage() + " " + summaryLine.getPluralPastMessage() + " " + summaryLine.getSingularPastMessage());
         }
         else
         {
            lines.add(String.valueOf(line.getMessage()));
         }
      }
      return (lines);
   }



   /*******************************************************************************
    ** Render an HTML widget.
    *******************************************************************************/
   private String html(String widget, Map<String, String> params) throws Exception
   {
      RenderWidgetInput input = new RenderWidgetInput().withWidgetMetaData(qInstance.getWidget(config.qualify(widget)));
      input.setQueryParams(new HashMap<>(params));
      return (((RawHTML) new RenderWidgetAction().execute(input).getWidgetData()).getHtml());
   }
}
