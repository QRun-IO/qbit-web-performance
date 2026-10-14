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

package com.kingsrook.qbits.webperf.widgets;


import java.io.Serializable;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import com.kingsrook.qbits.webperf.WebPerfQBitConfig;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qbits.webperf.store.WebPerfQueries;
import com.kingsrook.qbits.webperf.store.WebPerfStore;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.ChartData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;


/*******************************************************************************
 ** The Performance home's widgets and the record "Performance" panel
 ** (design §5). Real data only: every number is a stored Lighthouse run,
 ** finding or Google field data row, labelled with its source; with nothing
 ** to show a widget says why (never tested, no API key, Google has no data).
 *******************************************************************************/
public final class WebPerfWidgets
{
   public static final String SETUP           = "Setup";
   public static final String SITE_SCORES     = "SiteScores";
   public static final String TREND           = "Trend";
   public static final String SLOWEST_PAGES   = "SlowestPages";
   public static final String OPPORTUNITIES   = "TopOpportunities";
   public static final String RECENT_RUNS     = "RecentRuns";
   public static final String FIELD_STATUS    = "FieldDataStatus";
   public static final String RECORD_PANEL    = "RecordPanel";

   /** The home widgets, in order. */
   public static final List<String> HOME = List.of(SETUP, SITE_SCORES, TREND, SLOWEST_PAGES, OPPORTUNITIES, FIELD_STATUS, RECENT_RUNS);

   static final int TREND_WEEKS = 26;

   private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);



   /*******************************************************************************
    ** Widgets only.
    *******************************************************************************/
   private WebPerfWidgets()
   {
   }



   /*******************************************************************************
    ** The runtime a widget belongs to (its webPerformanceInstance default value).
    *******************************************************************************/
   static WebPerfRuntime runtime(RenderWidgetInput input)
   {
      Map<String, Serializable> defaults = input.getWidgetMetaData() == null ? null : input.getWidgetMetaData().getDefaultValues();
      Object                    prefix   = defaults == null ? null : defaults.get(WebPerfRuntime.INSTANCE_FIELD);
      return (WebPerfRuntime.of(QContext.getQInstance(), String.valueOf(prefix)));
   }



   /*******************************************************************************
    ** HTML output.
    *******************************************************************************/
   static RenderWidgetOutput html(String html)
   {
      return (new RenderWidgetOutput(new RawHTML(null, html)));
   }



   /*******************************************************************************
    ** A record link.
    *******************************************************************************/
   static String recordHref(String table, Object id)
   {
      return ("/app/" + table + "/" + id);
   }



   /*******************************************************************************
    ** A page cell: its label or path, linking to the tested page.
    *******************************************************************************/
   static String pageCell(WebPerfQBitConfig config, Map<String, Object> row)
   {
      Object label = row.get("label");
      String url   = (String) row.get("url");
      String text  = label != null ? label.toString() : url == null ? "" : url.replaceFirst("^https?://[^/]+", "");
      return (Html.link(recordHref(config.applyPrefix(WebPerfSchema.TARGET), row.get("target_id")), text.isEmpty() ? "/" : text));
   }



   /*******************************************************************************
    ** The note shown when lab tests cannot run.
    *******************************************************************************/
   static String noKeyNote(WebPerfRuntime runtime)
   {
      return (runtime.labRunner() == null ? Html.muted("No Google API key is configured, so lab tests are off. Queued runs wait until one is.") : "");
   }



   /*******************************************************************************
    ** The median of values (null when empty).
    *******************************************************************************/
   static Integer median(List<Integer> values)
   {
      List<Integer> sorted = values.stream().filter(java.util.Objects::nonNull).sorted().toList();
      if(sorted.isEmpty())
      {
         return (null);
      }
      int middle = sorted.size() / 2;
      return (sorted.size() % 2 == 1 ? sorted.get(middle) : (int) Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0));
   }



   /*******************************************************************************
    ** Site Scores: for each strategy, the median of the latest score of each
    ** KEY page (all enabled pages when none is KEY), per category.
    *******************************************************************************/
   public static class SiteScores extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         WebPerfRuntime runtime = runtime(input);
         try
         {
            List<Map<String, Object>> latest = new WebPerfQueries(runtime.store()).latestRuns();
            if(latest.isEmpty())
            {
               return (html(Html.muted("No page has been tested yet. Use “Sync pages from sitemap”, then “Run site audit”, on Tested Pages.")
                  + noKeyNote(runtime)));
            }
            boolean       anyKey = latest.stream().anyMatch(r -> "KEY".equals(r.get("tier")));
            StringBuilder html   = new StringBuilder();
            Instant       newest = null;
            String        version = null;
            for(String strategy : runtime.getConfig().getStrategies())
            {
               List<Map<String, Object>> runs = latest.stream().filter(r -> strategy.equals(r.get("strategy")) && (!anyKey || "KEY".equals(r.get("tier")))).toList();
               if(runs.isEmpty())
               {
                  continue;
               }
               List<Html.Tile> tiles = new ArrayList<>();
               for(String[] category : new String[][] { { "Performance", "score_performance" }, { "Accessibility", "score_accessibility" },
                  { "Best Practices", "score_best_practices" }, { "SEO", "score_seo" } })
               {
                  tiles.add(new Html.Tile(category[0], Html.score(median(runs.stream().map(r -> WebPerfStore.asInt(r.get(category[1]))).toList())), null));
               }
               html.append("<div style=\"margin:4px 0 6px;font-weight:600\">").append(Html.escape(strategy.charAt(0) + strategy.substring(1).toLowerCase(Locale.ROOT)))
                  .append(" <span style=\"").append(Html.MUTED).append(";font-weight:400;font-size:13px\">median of ").append(runs.size())
                  .append(anyKey ? " key page" : " page").append(runs.size() == 1 ? "" : "s").append("</span></div>").append(Html.tiles(tiles));
               for(Map<String, Object> run : runs)
               {
                  Instant completed = (Instant) run.get("completed_at");
                  if(newest == null || (completed != null && completed.isAfter(newest)))
                  {
                     newest = completed;
                     version = (String) run.get("lighthouse_version");
                  }
               }
            }
            html.append(Html.muted("Lab data (Lighthouse " + (version == null ? "?" : version) + ", latest test " + Html.ago(newest, Instant.now())
               + "). Lab scores vary from run to run; trends and regressions use medians."));
            return (html(html + noKeyNote(runtime)));
         }
         catch(SQLException e)
         {
            throw (new QException("Error loading site scores", e));
         }
      }
   }



   /*******************************************************************************
    ** Trend: weekly median Performance score per strategy, from the first week
    ** with a test (no week before testing began is drawn).
    *******************************************************************************/
   public static class Trend extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         WebPerfRuntime runtime = runtime(input);
         ZoneId         zone    = ZoneId.of(runtime.getConfig().getTimeZone());
         try
         {
            List<Map<String, Object>> runs = new WebPerfQueries(runtime.store()).successfulRunsSince(Instant.now().minus(Duration.ofDays(7L * TREND_WEEKS)));
            Map<LocalDate, Map<String, List<Integer>>> weeks = new TreeMap<>();
            for(Map<String, Object> run : runs)
            {
               Integer score = WebPerfStore.asInt(run.get("score_performance"));
               if(score == null)
               {
                  continue;
               }
               LocalDate week = ((Instant) run.get("completed_at")).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
               weeks.computeIfAbsent(week, w -> new TreeMap<>()).computeIfAbsent((String) run.get("strategy"), s -> new ArrayList<>()).add(score);
            }

            List<String>              labels   = new ArrayList<>();
            List<ChartData.Data.Dataset> datasets = new ArrayList<>();
            Map<String, List<Number>> series   = new TreeMap<>();
            for(String strategy : runtime.getConfig().getStrategies())
            {
               series.put(strategy, new ArrayList<>());
            }
            for(Map.Entry<LocalDate, Map<String, List<Integer>>> week : weeks.entrySet())
            {
               labels.add(DAY.format(week.getKey()));
               for(Map.Entry<String, List<Number>> entry : series.entrySet())
               {
                  entry.getValue().add(median(week.getValue().getOrDefault(entry.getKey(), List.of())));
               }
            }
            String[] colors = { "#0062FF", "#10B8A6" };
            int      i      = 0;
            for(Map.Entry<String, List<Number>> entry : series.entrySet())
            {
               datasets.add(new ChartData.Data.Dataset().withLabel(entry.getKey().charAt(0) + entry.getKey().substring(1).toLowerCase(Locale.ROOT) + " Performance")
                  .withData(entry.getValue()).withColor(colors[i++ % colors.length]));
            }
            ChartData chart = new ChartData().withChartData(new ChartData.Data().withLabels(labels).withDatasets(datasets));
            chart.setSublabel(labels.isEmpty() ? "No tests yet" : "Weekly median of every successful lab run (Lighthouse), from the first tested week");
            return (new RenderWidgetOutput(chart));
         }
         catch(SQLException e)
         {
            throw (new QException("Error loading the performance trend", e));
         }
      }
   }



   /*******************************************************************************
    ** Slowest Pages: the ten lowest latest mobile (else first strategy)
    ** Performance scores, with LCP and the top finding.
    *******************************************************************************/
   public static class SlowestPages extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         WebPerfRuntime    runtime  = runtime(input);
         WebPerfQBitConfig config   = runtime.getConfig();
         String            strategy = config.getStrategies().contains("MOBILE") ? "MOBILE" : config.getStrategies().get(0);
         try
         {
            WebPerfQueries            queries = new WebPerfQueries(runtime.store());
            List<Map<String, Object>> rows    = queries.latestRuns().stream().filter(r -> strategy.equals(r.get("strategy"))).limit(10).toList();
            if(rows.isEmpty())
            {
               return (html(Html.muted("No page has been tested on " + strategy.toLowerCase(Locale.ROOT) + " yet.")));
            }
            List<List<String>> cells = new ArrayList<>();
            for(Map<String, Object> row : rows)
            {
               List<Map<String, Object>> findings = queries.findingsOf(WebPerfStore.asLong(row.get("id")), 1);
               cells.add(List.of(pageCell(config, row), Html.score(WebPerfStore.asInt(row.get("score_performance"))),
                  Html.escape(Html.millis(WebPerfStore.asInt(row.get("lcp_ms")))),
                  findings.isEmpty() ? "<span style=\"" + Html.MUTED + "\">none</span>" : Html.escape(findings.get(0).get("title"))));
            }
            return (html(Html.table(List.of("Page", "Performance (" + strategy.toLowerCase(Locale.ROOT) + ")", "LCP (lab)", "Top finding"), cells)
               + Html.muted("Latest lab run of each page. ") + Html.link("/app/" + config.applyPrefix(WebPerfSchema.TARGET), "All tested pages")));
         }
         catch(SQLException e)
         {
            throw (new QException("Error loading the slowest pages", e));
         }
      }
   }



   /*******************************************************************************
    ** Top Opportunities: findings across the latest runs of every page,
    ** grouped by audit, by pages affected.
    *******************************************************************************/
   public static class TopOpportunities extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         WebPerfRuntime runtime = runtime(input);
         try
         {
            List<Map<String, Object>> rows = new WebPerfQueries(runtime.store()).topOpportunities(10);
            if(rows.isEmpty())
            {
               return (html(Html.muted("No findings: either nothing has been tested yet, or every audit passed in the latest runs.")));
            }
            String             findings = runtime.getConfig().applyPrefix(WebPerfSchema.LAB_FINDING);
            List<List<String>> cells    = new ArrayList<>();
            for(Map<String, Object> row : rows)
            {
               long savings = WebPerfStore.asLong(row.get("savings_ms")) == null ? 0 : WebPerfStore.asLong(row.get("savings_ms"));
               cells.add(List.of(Html.escape(row.get("title")) + "<div style=\"" + Html.MUTED + ";font-size:12px\">" + Html.escape(row.get("audit_id")) + "</div>",
                  Html.escape(row.get("pages")), savings > 0 ? Html.escape(Html.millis((int) savings)) : "<span style=\"" + Html.MUTED + "\">&ndash;</span>"));
            }
            return (html(Html.table(List.of("Finding", "Pages", "Est. savings (total)"), cells)
               + Html.muted("From the latest lab run of each page and strategy. ") + Html.link("/app/" + findings, "All findings")));
         }
         catch(SQLException e)
         {
            throw (new QException("Error loading the top opportunities", e));
         }
      }
   }



   /*******************************************************************************
    ** Recent Runs: the newest five, with status and scores.
    *******************************************************************************/
   public static class RecentRuns extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         WebPerfRuntime    runtime = runtime(input);
         WebPerfQBitConfig config  = runtime.getConfig();
         try
         {
            WebPerfQueries            queries = new WebPerfQueries(runtime.store());
            List<Map<String, Object>> rows    = queries.recentRuns(5);
            String                    runs    = config.applyPrefix(WebPerfSchema.LAB_RUN);
            if(rows.isEmpty())
            {
               return (html(Html.muted("No runs yet.") + noKeyNote(runtime)));
            }
            Instant            now   = Instant.now();
            List<List<String>> cells = new ArrayList<>();
            for(Map<String, Object> row : rows)
            {
               String status = (String) row.get("status");
               String scores = "SUCCEEDED".equals(status)
                  ? Html.score(WebPerfStore.asInt(row.get("score_performance"))) + " <span style=\"" + Html.MUTED + ";font-size:12px;white-space:nowrap\">A11y "
                     + Html.escape(row.get("score_accessibility")) + " · BP " + Html.escape(row.get("score_best_practices")) + " · SEO "
                     + Html.escape(row.get("score_seo")) + "</span>"
                  : "<span style=\"" + Html.MUTED + "\">" + Html.escape(row.get("error_message") == null ? "" : row.get("error_message")) + "</span>";
               Instant when = row.get("completed_at") != null ? (Instant) row.get("completed_at") : (Instant) row.get("queued_at");
               cells.add(List.of(pageCell(config, row) + "<div style=\"" + Html.MUTED + ";font-size:12px\">" + Html.escape(row.get("strategy")) + "</div>",
                  Html.link(recordHref(runs, row.get("id")), status), scores, Html.escape(Html.ago(when, now))));
            }
            int queued = queries.queuedCount();
            return (html(Html.table(List.of("Page", "Status", "Scores", "When"), cells)
               + Html.muted(queued + " run" + (queued == 1 ? "" : "s") + " waiting or running. ") + Html.link("/app/" + runs, "All lab runs") + noKeyNote(runtime)));
         }
         catch(SQLException e)
         {
            throw (new QException("Error loading recent runs", e));
         }
      }
   }



   /*******************************************************************************
    ** Setup: what is configured (pages, lab runner, budgets, field data) and
    ** the next step for each (WebPerfSetupStatus).
    *******************************************************************************/
   public static class Setup extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         try
         {
            return (html(WebPerfSetupStatus.html(runtime(input))));
         }
         catch(SQLException e)
         {
            throw (new QException("Error loading setup status", e));
         }
      }
   }



   /*******************************************************************************
    ** Field Data Status: what Google's field data (CrUX) says about the site,
    ** honestly NO_DATA when Google has none.
    *******************************************************************************/
   public static class FieldDataStatus extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         WebPerfRuntime runtime = runtime(input);
         try
         {
            String                    origin = runtime.service().origin();
            List<Map<String, Object>> rows   = new WebPerfQueries(runtime.store()).fieldData("ORIGIN", origin);
            Instant                   now    = Instant.now();
            if(rows.isEmpty())
            {
               return (html(Html.muted(runtime.cruxClient() == null
                  ? "Google field data (CrUX): not checked. No Google API key is configured."
                  : "Google field data (CrUX): not checked yet. It is checked daily, or use “Refresh Google field data”.")));
            }
            Map<String, Object> all = rows.stream().filter(r -> "ALL".equals(r.get("form_factor"))).findFirst().orElse(rows.get(0));
            if("NO_DATA".equals(all.get("status")))
            {
               return (html("<div style=\"font-weight:600\">Google field data (CrUX): not available</div>" + Html.muted(
                  "Google publishes field data only for pages and sites with enough Chrome traffic; it has none for " + origin + ". Last checked "
                     + Html.ago((Instant) all.get("last_checked_at"), now) + ". Lab results above are Lighthouse runs; real-user figures come from this site's own "
                     + "measurements.")));
            }
            List<Html.Tile> tiles = List.of(
               new Html.Tile("LCP p75", Html.escape(Html.millis(WebPerfStore.asInt(all.get("p75_lcp_ms")))), "good ≤ 2.5 s"),
               new Html.Tile("INP p75", Html.escape(Html.millis(WebPerfStore.asInt(all.get("p75_inp_ms")))), "good ≤ 200 ms"),
               new Html.Tile("CLS p75", Html.escape(all.get("p75_cls") == null ? "–" : all.get("p75_cls")), "good ≤ 0.1"),
               new Html.Tile("TTFB p75", Html.escape(Html.millis(WebPerfStore.asInt(all.get("p75_ttfb_ms")))), "good ≤ 0.8 s"));
            return (html(Html.tiles(tiles) + Html.muted("Google field data (CrUX) for " + origin + ", all devices, 28 days to " + all.get("period_end")
               + ". Last checked " + Html.ago((Instant) all.get("last_checked_at"), now) + ".")));
         }
         catch(SQLException e)
         {
            throw (new QException("Error loading Google field data", e));
         }
      }
   }



   /*******************************************************************************
    ** The record "Performance" panel: this record's page, its latest scores per
    ** strategy, lab metrics, what to fix, and a trend of its mobile score.
    *******************************************************************************/
   public static class RecordPanel extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render for the tableName / id parameters.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         WebPerfRuntime      runtime = runtime(input);
         WebPerfQBitConfig   config  = runtime.getConfig();
         Map<String, String> params  = input.getQueryParams();
         String              table   = params == null ? null : params.get("tableName");
         String              id      = params == null ? null : params.get("id");
         if(table == null || id == null || !id.matches("\\d{1,18}"))
         {
            return (html(Html.muted("Open a record to see its page speed.")));
         }
         boolean direct = config.applyPrefix(WebPerfSchema.TARGET).equals(table);
         try
         {
            WebPerfQueries            queries = new WebPerfQueries(runtime.store());
            List<Map<String, Object>> pages   = queries.pagesOf(table, Long.parseLong(id), direct);
            if(pages.isEmpty())
            {
               return (html(Html.muted("This record has no tested page: it is not in the sitemap (for example a draft), so it is not tested.")));
            }
            StringBuilder html = new StringBuilder();
            Instant       now  = Instant.now();
            for(Map<String, Object> page : pages)
            {
               long pageId = WebPerfStore.asLong(page.get("id"));
               if(!direct)
               {
                  html.append("<div style=\"margin-bottom:6px\">").append(Html.link(recordHref(config.applyPrefix(WebPerfSchema.TARGET), pageId), page.get("url")))
                     .append("</div>");
               }
               if(Boolean.FALSE.equals(page.get("enabled")))
               {
                  html.append(Html.muted("This page is disabled, so it is not tested."));
               }

               List<Html.Tile>     tiles       = new ArrayList<>();
               Map<String, Object> mobileRun   = null;
               List<Integer>       mobileTrend = new ArrayList<>();
               String              waiting     = null;
               for(String strategy : config.getStrategies())
               {
                  List<Map<String, Object>> runs   = queries.runsOf(pageId, strategy, 12);
                  Map<String, Object>       latest = runs.stream().filter(r -> "SUCCEEDED".equals(r.get("status"))).findFirst().orElse(null);
                  if(!runs.isEmpty() && ("QUEUED".equals(runs.get(0).get("status")) || "RUNNING".equals(runs.get(0).get("status"))))
                  {
                     waiting = "A test is " + runs.get(0).get("status").toString().toLowerCase(Locale.ROOT) + " (queued " + Html.ago((Instant) runs.get(0).get("queued_at"), now) + ").";
                  }
                  String label = strategy.charAt(0) + strategy.substring(1).toLowerCase(Locale.ROOT);
                  if(latest == null)
                  {
                     tiles.add(new Html.Tile(label, "<span style=\"" + Html.MUTED + "\">not tested</span>", null));
                     continue;
                  }
                  tiles.add(new Html.Tile(label, Html.score(WebPerfStore.asInt(latest.get("score_performance"))),
                     "A11y " + latest.get("score_accessibility") + " · BP " + latest.get("score_best_practices") + " · SEO " + latest.get("score_seo") + " · "
                        + Html.ago((Instant) latest.get("completed_at"), now)));
                  if("MOBILE".equals(strategy) || mobileRun == null)
                  {
                     mobileRun = latest;
                     mobileTrend = new ArrayList<>(runs.stream().filter(r -> "SUCCEEDED".equals(r.get("status")))
                        .map(r -> WebPerfStore.asInt(r.get("score_performance"))).filter(java.util.Objects::nonNull).toList());
                     Collections.reverse(mobileTrend);
                  }
               }
               html.append(Html.tiles(tiles));
               if(waiting != null)
               {
                  html.append(Html.muted(waiting));
               }
               if(mobileRun == null)
               {
                  html.append(Html.muted("Never tested. Use “Test page speed” in this record's actions."));
                  continue;
               }
               html.append(Html.muted("Lab metrics (Lighthouse " + mobileRun.get("lighthouse_version") + "): LCP " + Html.millis(WebPerfStore.asInt(mobileRun.get("lcp_ms")))
                  + " · FCP " + Html.millis(WebPerfStore.asInt(mobileRun.get("fcp_ms"))) + " · TBT " + Html.millis(WebPerfStore.asInt(mobileRun.get("tbt_ms")))
                  + " · CLS " + (mobileRun.get("cls") == null ? "–" : mobileRun.get("cls"))
                  + (mobileRun.get("lcp_element") == null ? "" : " · LCP element " + mobileRun.get("lcp_element"))));
               if(mobileTrend.size() >= 2)
               {
                  html.append("<div style=\"margin:6px 0\">").append(Html.sparkline(mobileTrend)).append("</div>");
               }
               List<Map<String, Object>> findings = queries.findingsOf(WebPerfStore.asLong(mobileRun.get("id")), 5);
               if(findings.isEmpty())
               {
                  html.append(Html.muted("Every scored audit passed in the latest run."));
               }
               else
               {
                  List<List<String>> cells = new ArrayList<>();
                  for(Map<String, Object> finding : findings)
                  {
                     Integer savings = WebPerfStore.asInt(finding.get("savings_ms"));
                     cells.add(List.of(Html.escape(finding.get("title")) + (finding.get("display_value") == null ? ""
                           : " <span style=\"" + Html.MUTED + ";font-size:12px\">" + Html.escape(finding.get("display_value")) + "</span>"),
                        savings == null ? "<span style=\"" + Html.MUTED + "\">&ndash;</span>" : Html.escape(Html.millis(savings))));
                  }
                  html.append("<div style=\"margin-top:8px;font-weight:600\">What to fix</div>").append(Html.table(List.of("Finding", "Est. savings"), cells));
               }
            }
            return (html(html.toString() + noKeyNote(runtime)));
         }
         catch(SQLException e)
         {
            throw (new QException("Error loading this record's page speed", e));
         }
      }
   }
}
