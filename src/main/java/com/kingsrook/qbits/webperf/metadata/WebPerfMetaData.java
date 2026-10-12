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

package com.kingsrook.qbits.webperf.metadata;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.webperf.WebPerfQBitConfig;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qbits.webperf.processes.WebPerfProcesses;
import com.kingsrook.qbits.webperf.widgets.WebPerfWidgets;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.audits.QAuditRules;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;


/*******************************************************************************
 ** The QBit's tables, lookups and app (design §4, §5). Every table is a
 ** standard, visible QQQ table on the host's RDBMS backend, protected by the
 ** "webperf" permissions (webperf.read / webperf.write by default).
 **
 ** - Measurements (lab runs, findings, reports, field snapshots,
 **   comparisons, change events) are written only by the QBit's processes:
 **   read-only in the UI and not audited.
 ** - Tested pages and budgets are edited by people (enable a page, change a
 **   budget): only those fields are editable, and both are field-audited.
 **   Regressions change status only through record actions (dismiss,
 **   resolve), so they are read-only in the UI but field-audited.
 **
 ** Column names are the snake_case of the field names, and must match the
 ** host-included changelog (WebPerfSchema.CHANGELOG); WebPerfSchemaTest
 ** checks both against a real database.
 *******************************************************************************/
public final class WebPerfMetaData
{
   private final WebPerfQBitConfig config;



   /*******************************************************************************
    ** Meta-data for a config.
    **
    ** @param config the config
    *******************************************************************************/
   public WebPerfMetaData(WebPerfQBitConfig config)
   {
      this.config = config;
   }



   /*******************************************************************************
    ** Add the tables, their lookups and the Performance app.
    **
    ** @param qInstance the instance
    *******************************************************************************/
   public void addTo(QInstance qInstance)
   {
      for(QTableMetaData table : tables().values())
      {
         qInstance.addTable(table);
      }
      for(String base : List.of(WebPerfSchema.TARGET, WebPerfSchema.LAB_RUN, WebPerfSchema.BUDGET, WebPerfSchema.CHANGE_EVENT, WebPerfSchema.REGRESSION))
      {
         qInstance.addPossibleValueSource(QPossibleValueSource.newForTable(config.applyPrefix(base)));
      }
      for(QProcessMetaData process : WebPerfProcesses.define(config, qInstance))
      {
         qInstance.addProcess(process);
      }
      addWidget(qInstance, WebPerfWidgets.SITE_SCORES, "Site Scores", WidgetType.HTML, WebPerfWidgets.SiteScores.class);
      addWidget(qInstance, WebPerfWidgets.TREND, "Performance Trend", WidgetType.LINE_CHART, WebPerfWidgets.Trend.class);
      addWidget(qInstance, WebPerfWidgets.SLOWEST_PAGES, "Slowest Pages", WidgetType.HTML, WebPerfWidgets.SlowestPages.class);
      addWidget(qInstance, WebPerfWidgets.OPPORTUNITIES, "Top Opportunities", WidgetType.HTML, WebPerfWidgets.TopOpportunities.class);
      addWidget(qInstance, WebPerfWidgets.FIELD_STATUS, "Google Field Data", WidgetType.HTML, WebPerfWidgets.FieldDataStatus.class);
      addWidget(qInstance, WebPerfWidgets.RECENT_RUNS, "Recent Runs", WidgetType.HTML, WebPerfWidgets.RecentRuns.class);
      addWidget(qInstance, WebPerfWidgets.RECORD_PANEL, "Performance", WidgetType.HTML, WebPerfWidgets.RecordPanel.class);

      List<String> panelTables = new ArrayList<>(config.getContentTables());
      panelTables.add(0, config.applyPrefix(WebPerfSchema.TARGET));
      for(String tableName : panelTables)
      {
         qInstance.getTable(tableName).addSection(new QFieldSection(config.qualify("Performance"), new QIcon("speed"), Tier.T2)
            .withLabel("Performance")
            .withWidgetName(config.qualify(WebPerfWidgets.RECORD_PANEL)));
      }

      qInstance.addApp(app());
      if(config.getParentAppName() != null)
      {
         qInstance.getApp(config.getParentAppName()).withChild(qInstance.getApp(config.getAppName()));
      }
   }



   /*******************************************************************************
    ** A card widget, full width, carrying the install's prefix so its
    ** renderer finds the runtime.
    *******************************************************************************/
   private void addWidget(QInstance qInstance, String name, String label, WidgetType type, Class<? extends AbstractWidgetRenderer> renderer)
   {
      qInstance.addWidget(new QWidgetMetaData()
         .withName(config.qualify(name))
         .withLabel(label)
         .withType(type.getType())
         .withCodeReference(new QCodeReference(renderer))
         .withGridColumns(12)
         .withIsCard(true)
         .withDefaultValues(new java.util.HashMap<>(Map.of(WebPerfRuntime.INSTANCE_FIELD, config.getTableNamePrefix())))
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
            .withPermissionBaseName(config.getPermissionBaseName() + ".view")));
   }



   /*******************************************************************************
    ** The Performance app: its one home (the widgets) and the data tables.
    ** No process is in its nav.
    **
    ** @return the app
    *******************************************************************************/
   QAppMetaData app()
   {
      List<String> tables = new ArrayList<>();
      for(String base : WebPerfSchema.TABLES)
      {
         tables.add(config.applyPrefix(base));
      }
      return (new QAppMetaData()
         .withName(config.getAppName())
         .withLabel(config.getAppLabel())
         .withIcon(new QIcon("speed"))
         .withPermissionRules(rules())
         .withWidgets(WebPerfWidgets.HOME.stream().map(config::qualify).toList())
         .withSection(new QAppSection()
            .withName("data")
            .withLabel("Data")
            .withIcon(new QIcon("table_rows"))
            .withTables(tables)));
   }



   /*******************************************************************************
    ** All tables by name, in app order.
    **
    ** @return the tables
    *******************************************************************************/
   public Map<String, QTableMetaData> tables()
   {
      Map<String, QTableMetaData> tables = new LinkedHashMap<>();
      for(QTableMetaData table : List.of(target(), labRun(), labFinding(), regression(), budget(), changeEvent(), comparison(), fieldSnapshot(),
         labReport()))
      {
         tables.put(table.getName(), table);
      }
      return (tables);
   }



   /*******************************************************************************
    ** Tested Pages.
    *******************************************************************************/
   QTableMetaData target()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("siteKey", QFieldType.STRING, "Site").withMaxLength(100),
         f("url", QFieldType.STRING, "URL").withMaxLength(1000).withIsRequired(true),
         f("path", QFieldType.STRING, "Path").withMaxLength(500),
         f("source", QFieldType.STRING, "Source").withMaxLength(20),
         f("contentTable", QFieldType.STRING, "Content Table").withMaxLength(100),
         f("contentId", QFieldType.LONG, "Content Id"),
         f("label", QFieldType.STRING, "Label").withMaxLength(200),
         f("tier", QFieldType.STRING, "Tier").withMaxLength(20).withDefaultValue("STANDARD"),
         f("enabled", QFieldType.BOOLEAN, "Enabled").withDefaultValue(true),
         f("inSitemap", QFieldType.BOOLEAN, "In Sitemap"),
         f("lastSeenInSitemapAt", QFieldType.DATE_TIME, "Last Seen in Sitemap"),
         f("latestMobilePerformance", QFieldType.INTEGER, "Mobile Performance"),
         f("latestDesktopPerformance", QFieldType.INTEGER, "Desktop Performance"),
         f("latestAccessibility", QFieldType.INTEGER, "Accessibility"),
         f("latestTestedAt", QFieldType.DATE_TIME, "Last Tested"),
         f("openRegressionCount", QFieldType.INTEGER, "Open Regressions")));

      QTableMetaData table = table(WebPerfSchema.TARGET, "Tested Pages", "travel_explore", fields, true,
         Set.of("label", "tier", "enabled"))
         .withRecordLabelFormatAndFields("%s", "url")
         .withUniqueKey(new UniqueKey("siteKey", "url"))
         .withSection(section("page", "Page", "travel_explore", Tier.T1, "id", "url", "label", "tier", "enabled"))
         .withSection(section("latest", "Latest Results", "speed", Tier.T2, "latestMobilePerformance", "latestDesktopPerformance",
            "latestAccessibility", "latestTestedAt", "openRegressionCount"))
         .withSection(section("source", "Source", "link", Tier.T2, "siteKey", "path", "source", "contentTable", "contentId", "inSitemap",
            "lastSeenInSitemapAt"))
         .withSection(dates());
      return (table);
   }



   /*******************************************************************************
    ** Lab Runs.
    *******************************************************************************/
   QTableMetaData labRun()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("targetId", QFieldType.LONG, "Page").withPossibleValueSourceName(config.applyPrefix(WebPerfSchema.TARGET)),
         f("url", QFieldType.STRING, "URL").withMaxLength(1000),
         f("finalUrl", QFieldType.STRING, "Final URL").withMaxLength(1000),
         f("strategy", QFieldType.STRING, "Strategy").withMaxLength(10),
         f("runner", QFieldType.STRING, "Runner").withMaxLength(20),
         f("triggerType", QFieldType.STRING, "Trigger").withMaxLength(20),
         f("triggerRef", QFieldType.STRING, "Trigger Reference").withMaxLength(200),
         f("batchId", QFieldType.STRING, "Batch").withMaxLength(64),
         f("status", QFieldType.STRING, "Status").withMaxLength(20),
         f("notBefore", QFieldType.DATE_TIME, "Not Before"),
         f("queuedAt", QFieldType.DATE_TIME, "Queued"),
         f("startedAt", QFieldType.DATE_TIME, "Started"),
         f("completedAt", QFieldType.DATE_TIME, "Completed"),
         f("attempts", QFieldType.INTEGER, "Attempts"),
         f("httpStatus", QFieldType.INTEGER, "HTTP Status"),
         f("errorMessage", QFieldType.STRING, "Error").withMaxLength(2000),
         f("lighthouseVersion", QFieldType.STRING, "Lighthouse Version").withMaxLength(20),
         f("chromeUserAgent", QFieldType.STRING, "Chrome").withMaxLength(300),
         f("benchmarkIndex", QFieldType.INTEGER, "Benchmark Index"),
         f("scorePerformance", QFieldType.INTEGER, "Performance"),
         f("scoreAccessibility", QFieldType.INTEGER, "Accessibility"),
         f("scoreBestPractices", QFieldType.INTEGER, "Best Practices"),
         f("scoreSeo", QFieldType.INTEGER, "SEO"),
         f("fcpMs", QFieldType.INTEGER, "FCP (ms)"),
         f("lcpMs", QFieldType.INTEGER, "LCP (ms)"),
         f("tbtMs", QFieldType.INTEGER, "TBT (ms)"),
         f("cls", QFieldType.DECIMAL, "CLS"),
         f("speedIndexMs", QFieldType.INTEGER, "Speed Index (ms)"),
         f("lcpElement", QFieldType.STRING, "LCP Element").withMaxLength(300),
         f("runWarnings", QFieldType.STRING, "Warnings").withMaxLength(2000),
         f("deploymentId", QFieldType.STRING, "Frontend Build").withMaxLength(64),
         f("cdnStage", QFieldType.STRING, "CDN Stage").withMaxLength(20)));

      return (table(WebPerfSchema.LAB_RUN, "Lab Runs", "speed", fields, false, Set.of())
         .withRecordLabelFormatAndFields("%s %s", "strategy", "url")
         .withSection(section("run", "Run", "speed", Tier.T1, "id", "url", "strategy", "status", "completedAt", "triggerType"))
         .withSection(section("scores", "Scores", "grade", Tier.T2, "scorePerformance", "scoreAccessibility", "scoreBestPractices", "scoreSeo"))
         .withSection(section("metrics", "Lab Metrics", "timer", Tier.T2, "fcpMs", "lcpMs", "tbtMs", "cls", "speedIndexMs", "lcpElement"))
         .withSection(section("details", "Details", "info", Tier.T2, "targetId", "finalUrl", "runner", "triggerRef", "batchId", "notBefore",
            "queuedAt", "startedAt", "attempts", "httpStatus", "errorMessage", "lighthouseVersion", "chromeUserAgent", "benchmarkIndex",
            "runWarnings", "deploymentId", "cdnStage"))
         .withSection(dates()));
   }



   /*******************************************************************************
    ** Findings.
    *******************************************************************************/
   QTableMetaData labFinding()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("runId", QFieldType.LONG, "Run").withPossibleValueSourceName(config.applyPrefix(WebPerfSchema.LAB_RUN)),
         f("targetId", QFieldType.LONG, "Page").withPossibleValueSourceName(config.applyPrefix(WebPerfSchema.TARGET)),
         f("strategy", QFieldType.STRING, "Strategy").withMaxLength(10),
         f("category", QFieldType.STRING, "Category").withMaxLength(20),
         f("auditId", QFieldType.STRING, "Audit").withMaxLength(100),
         f("lighthouseVersion", QFieldType.STRING, "Lighthouse Version").withMaxLength(20),
         f("kind", QFieldType.STRING, "Kind").withMaxLength(20),
         f("title", QFieldType.STRING, "Title").withMaxLength(300),
         f("score", QFieldType.DECIMAL, "Score"),
         f("displayValue", QFieldType.STRING, "Value").withMaxLength(200),
         f("savingsMs", QFieldType.INTEGER, "Savings (ms)"),
         f("savingsBytes", QFieldType.LONG, "Savings (bytes)"),
         f("metricSavings", QFieldType.TEXT, "Metric Savings"),
         f("topItems", QFieldType.TEXT, "Top Items")));

      return (table(WebPerfSchema.LAB_FINDING, "Findings", "lightbulb", fields, false, Set.of())
         .withRecordLabelFormatAndFields("%s", "title")
         .withSection(section("finding", "Finding", "lightbulb", Tier.T1, "id", "title", "kind", "category", "displayValue", "savingsMs",
            "savingsBytes"))
         .withSection(section("details", "Details", "info", Tier.T2, "runId", "targetId", "strategy", "auditId", "lighthouseVersion", "score",
            "metricSavings", "topItems"))
         .withSection(dates()));
   }



   /*******************************************************************************
    ** Lighthouse Reports (the complete gzipped PSI response, D8).
    *******************************************************************************/
   QTableMetaData labReport()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("runId", QFieldType.LONG, "Run").withPossibleValueSourceName(config.applyPrefix(WebPerfSchema.LAB_RUN)),
         f("content", QFieldType.BLOB, "Report (gzip JSON)"),
         f("bytes", QFieldType.INTEGER, "Bytes"),
         f("sha256", QFieldType.STRING, "SHA-256").withMaxLength(64)));

      return (table(WebPerfSchema.LAB_REPORT, "Lighthouse Reports", "description", fields, false, Set.of())
         .withRecordLabelFormatAndFields("Report %s", "runId")
         .withUniqueKey(new UniqueKey("runId"))
         .withSection(section("report", "Report", "description", Tier.T1, "id", "runId", "bytes", "sha256", "content"))
         .withSection(dates()));
   }



   /*******************************************************************************
    ** Google Field Data (CrUX).
    *******************************************************************************/
   QTableMetaData fieldSnapshot()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("source", QFieldType.STRING, "Source").withMaxLength(20),
         f("scope", QFieldType.STRING, "Scope").withMaxLength(10),
         f("scopeKey", QFieldType.STRING, "URL or Origin").withMaxLength(1000),
         f("formFactor", QFieldType.STRING, "Form Factor").withMaxLength(10),
         f("status", QFieldType.STRING, "Status").withMaxLength(10),
         f("periodStart", QFieldType.DATE, "Period Start"),
         f("periodEnd", QFieldType.DATE, "Period End"),
         f("p75LcpMs", QFieldType.INTEGER, "LCP p75 (ms)"),
         f("p75InpMs", QFieldType.INTEGER, "INP p75 (ms)"),
         f("p75Cls", QFieldType.DECIMAL, "CLS p75"),
         f("p75FcpMs", QFieldType.INTEGER, "FCP p75 (ms)"),
         f("p75TtfbMs", QFieldType.INTEGER, "TTFB p75 (ms)"),
         f("distributions", QFieldType.TEXT, "Distributions"),
         f("lastCheckedAt", QFieldType.DATE_TIME, "Last Checked")));

      return (table(WebPerfSchema.FIELD_SNAPSHOT, "Google Field Data", "public", fields, false, Set.of())
         .withRecordLabelFormatAndFields("%s %s", "scopeKey", "formFactor")
         .withSection(section("snapshot", "Snapshot", "public", Tier.T1, "id", "scopeKey", "scope", "formFactor", "status", "lastCheckedAt"))
         .withSection(section("p75", "p75 (28 days)", "insights", Tier.T2, "p75LcpMs", "p75InpMs", "p75Cls", "p75FcpMs", "p75TtfbMs",
            "periodStart", "periodEnd", "source", "distributions"))
         .withSection(dates()));
   }



   /*******************************************************************************
    ** Budgets.
    *******************************************************************************/
   QTableMetaData budget()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("name", QFieldType.STRING, "Name").withMaxLength(200).withIsRequired(true),
         f("scope", QFieldType.STRING, "Scope").withMaxLength(20),
         f("scopeValue", QFieldType.STRING, "Scope Value").withMaxLength(500),
         f("source", QFieldType.STRING, "Source").withMaxLength(10),
         f("strategy", QFieldType.STRING, "Strategy").withMaxLength(10),
         f("metric", QFieldType.STRING, "Metric").withMaxLength(30),
         f("comparator", QFieldType.STRING, "Comparator").withMaxLength(5),
         f("threshold", QFieldType.DECIMAL, "Threshold"),
         f("severity", QFieldType.STRING, "Severity").withMaxLength(10),
         f("enabled", QFieldType.BOOLEAN, "Enabled"),
         f("seeded", QFieldType.BOOLEAN, "Seeded")));

      return (table(WebPerfSchema.BUDGET, "Budgets", "rule", fields, true,
         Set.of("name", "scope", "scopeValue", "source", "strategy", "metric", "comparator", "threshold", "severity", "enabled"))
         .withRecordLabelFormatAndFields("%s", "name")
         .withSection(section("budget", "Budget", "rule", Tier.T1, "id", "name", "metric", "comparator", "threshold", "severity", "enabled"))
         .withSection(section("scope", "Scope", "filter_alt", Tier.T2, "scope", "scopeValue", "source", "strategy", "seeded"))
         .withSection(dates()));
   }



   /*******************************************************************************
    ** Regressions.
    *******************************************************************************/
   QTableMetaData regression()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("targetId", QFieldType.LONG, "Page").withPossibleValueSourceName(config.applyPrefix(WebPerfSchema.TARGET)),
         f("contentTable", QFieldType.STRING, "Content Table").withMaxLength(100),
         f("contentId", QFieldType.LONG, "Content Id"),
         f("source", QFieldType.STRING, "Source").withMaxLength(10),
         f("strategy", QFieldType.STRING, "Strategy or Form Factor").withMaxLength(10),
         f("metric", QFieldType.STRING, "Metric").withMaxLength(30),
         f("baselineValue", QFieldType.DECIMAL, "Baseline"),
         f("baselineRef", QFieldType.STRING, "Baseline From").withMaxLength(500),
         f("currentValue", QFieldType.DECIMAL, "Current"),
         f("currentRef", QFieldType.STRING, "Current From").withMaxLength(500),
         f("delta", QFieldType.DECIMAL, "Change"),
         f("budgetId", QFieldType.LONG, "Budget").withPossibleValueSourceName(config.applyPrefix(WebPerfSchema.BUDGET)),
         f("severity", QFieldType.STRING, "Severity").withMaxLength(10),
         f("status", QFieldType.STRING, "Status").withMaxLength(20),
         f("causeChangeEventId", QFieldType.LONG, "Likely Cause").withPossibleValueSourceName(config.applyPrefix(WebPerfSchema.CHANGE_EVENT)),
         f("detectedAt", QFieldType.DATE_TIME, "Detected"),
         f("confirmedAt", QFieldType.DATE_TIME, "Confirmed"),
         f("resolvedAt", QFieldType.DATE_TIME, "Resolved"),
         f("resolutionNote", QFieldType.STRING, "Resolution Note").withMaxLength(2000)));

      return (table(WebPerfSchema.REGRESSION, "Regressions", "trending_down", fields, true, Set.of())
         .withRecordLabelFormatAndFields("%s %s", "metric", "status")
         .withSection(section("regression", "Regression", "trending_down", Tier.T1, "id", "targetId", "metric", "strategy", "baselineValue",
            "currentValue", "delta", "severity", "status"))
         .withSection(section("details", "Details", "info", Tier.T2, "source", "contentTable", "contentId", "baselineRef", "currentRef",
            "budgetId", "causeChangeEventId", "detectedAt", "confirmedAt", "resolvedAt", "resolutionNote"))
         .withSection(dates()));
   }



   /*******************************************************************************
    ** Site Changes.
    *******************************************************************************/
   QTableMetaData changeEvent()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("kind", QFieldType.STRING, "Kind").withMaxLength(30),
         f("component", QFieldType.STRING, "Component").withMaxLength(200),
         f("fromValue", QFieldType.STRING, "From").withMaxLength(200),
         f("toValue", QFieldType.STRING, "To").withMaxLength(200),
         f("observedAt", QFieldType.DATE_TIME, "Observed"),
         f("source", QFieldType.STRING, "Source").withMaxLength(20),
         f("note", QFieldType.STRING, "Note").withMaxLength(2000)));

      return (table(WebPerfSchema.CHANGE_EVENT, "Site Changes", "change_circle", fields, false, Set.of())
         .withRecordLabelFormatAndFields("%s %s", "kind", "toValue")
         .withSection(section("change", "Change", "change_circle", Tier.T1, "id", "kind", "component", "fromValue", "toValue", "observedAt",
            "source", "note"))
         .withSection(dates()));
   }



   /*******************************************************************************
    ** Before and After.
    *******************************************************************************/
   QTableMetaData comparison()
   {
      List<QFieldMetaData> fields = withCommon(List.of(
         f("changeEventId", QFieldType.LONG, "Change").withPossibleValueSourceName(config.applyPrefix(WebPerfSchema.CHANGE_EVENT)),
         f("scope", QFieldType.STRING, "Scope").withMaxLength(20),
         f("source", QFieldType.STRING, "Source").withMaxLength(10),
         f("strategy", QFieldType.STRING, "Strategy or Form Factor").withMaxLength(10),
         f("metric", QFieldType.STRING, "Metric").withMaxLength(30),
         f("segment", QFieldType.STRING, "Segment").withMaxLength(100),
         f("beforeValue", QFieldType.DECIMAL, "Before"),
         f("beforeN", QFieldType.INTEGER, "Before n"),
         f("beforeWindow", QFieldType.STRING, "Before Window").withMaxLength(100),
         f("afterValue", QFieldType.DECIMAL, "After"),
         f("afterN", QFieldType.INTEGER, "After n"),
         f("afterWindow", QFieldType.STRING, "After Window").withMaxLength(100),
         f("delta", QFieldType.DECIMAL, "Change"),
         f("deltaPct", QFieldType.DECIMAL, "Change (%)")));

      return (table(WebPerfSchema.COMPARISON, "Before and After", "compare_arrows", fields, false, Set.of())
         .withRecordLabelFormatAndFields("%s %s", "metric", "segment")
         .withSection(section("comparison", "Comparison", "compare_arrows", Tier.T1, "id", "changeEventId", "metric", "segment", "beforeValue",
            "afterValue", "delta", "deltaPct"))
         .withSection(section("details", "Details", "info", Tier.T2, "scope", "source", "strategy", "beforeN", "beforeWindow", "afterN",
            "afterWindow"))
         .withSection(dates()));
   }



   /*******************************************************************************
    ** A table: id, create and modify dates, the webperf permissions, RDBMS
    ** backend with snake_case columns. A table with editable fields keeps
    ** insert/edit/delete with only those fields editable; the others are
    ** read-only in the UI (written by the QBit's processes). Audited tables
    ** get field-level audit.
    *******************************************************************************/
   private QTableMetaData table(String base, String label, String icon, List<QFieldMetaData> fields, boolean audited, Set<String> editableFields)
   {
      boolean editable = !editableFields.isEmpty();
      String name = config.applyPrefix(base);
      QTableMetaData table = new QTableMetaData()
         .withName(name)
         .withLabel(label)
         .withIcon(new QIcon(icon))
         .withBackendName(config.getBackendName())
         .withBackendDetails(new RDBMSTableBackendDetails().withTableName(name))
         .withPrimaryKeyField("id")
         .withFields(fields)
         .withPermissionRules(rules())
         .withAuditRules(new QAuditRules().withAuditLevel(audited ? AuditLevel.FIELD : AuditLevel.NONE));
      if(!editable)
      {
         table.withoutCapabilities(Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE);
      }
      for(QFieldMetaData field : fields)
      {
         field.setIsEditable(editable && editableFields.contains(field.getName()));
      }
      QInstanceEnricher.setInferredFieldBackendNames(table);
      return (table);
   }



   /*******************************************************************************
    ** The permission rules for every table and the app.
    *******************************************************************************/
   private QPermissionRules rules()
   {
      return (new QPermissionRules().withLevel(PermissionLevel.READ_WRITE_PERMISSIONS).withPermissionBaseName(config.getPermissionBaseName()));
   }



   /*******************************************************************************
    ** id first, the given fields, then the create and modify dates.
    *******************************************************************************/
   private static List<QFieldMetaData> withCommon(List<QFieldMetaData> fields)
   {
      List<QFieldMetaData> all = new ArrayList<>();
      all.add(f("id", QFieldType.LONG, "Id"));
      all.addAll(fields);
      all.add(f("createDate", QFieldType.DATE_TIME, "Create Date"));
      all.add(f("modifyDate", QFieldType.DATE_TIME, "Modify Date"));
      return (all);
   }



   /*******************************************************************************
    ** The dates section.
    *******************************************************************************/
   private static QFieldSection dates()
   {
      return (section("dates", "Dates", "calendar_month", Tier.T3, "createDate", "modifyDate"));
   }



   /*******************************************************************************
    ** A field section.
    *******************************************************************************/
   private static QFieldSection section(String name, String label, String icon, Tier tier, String... fields)
   {
      return (new QFieldSection(name, label, new QIcon(icon), tier, List.of(fields)));
   }



   /*******************************************************************************
    ** A field.
    *******************************************************************************/
   private static QFieldMetaData f(String name, QFieldType type, String label)
   {
      return (new QFieldMetaData(name, type).withLabel(label));
   }
}
