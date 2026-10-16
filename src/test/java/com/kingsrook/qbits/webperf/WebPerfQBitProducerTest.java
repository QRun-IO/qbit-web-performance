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


import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.webperf.sources.HttpBuildIdProbe;
import com.kingsrook.qbits.webperf.sources.SitemapUrlSource;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Registration into a fresh, non-CMS instance: the QBit, its nine visible
 ** tables, lookups and app pass QQQ's instance validation; the rules of the
 ** design hold (no process in any nav, measurements read-only and unaudited,
 ** people-edited tables audited); config validation, namespaces and
 ** duplicate installs.
 *******************************************************************************/
class WebPerfQBitProducerTest
{
   /*******************************************************************************
    ** Clear the context.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** Registers and validates.
    *******************************************************************************/
   @Test
   void testRegistersIntoAFreshInstance() throws Exception
   {
      assertThat(System.getProperty("qbit.artifactVersion")).isNotBlank();
      assertThat(WebPerfQBitProducer.VERSION).isEqualTo(System.getProperty("qbit.artifactVersion"));
      assertThat(new WebPerfQBitProducer().getQBitMetaData().getVersion()).isEqualTo(WebPerfQBitProducer.VERSION);
      QInstance qInstance = WebPerfTestSupport.newInstance();
      new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config()
         .withUrlSource(new SitemapUrlSource("https://www.example.com/sitemap.xml"))
         .withDeployProbe(new HttpBuildIdProbe("FRONTEND", "http://frontend:3000/api/build"))).produce(qInstance);

      new QInstanceValidator().validate(qInstance);

      assertThat(qInstance.getQBits()).containsKey("com.kingsrook.qbits:web-performance");
      for(String base : WebPerfSchema.TABLES)
      {
         assertNotNull(qInstance.getTable("web_performance_" + base), base);
      }

      QAppMetaData app = qInstance.getApp(WebPerfQBitConfig.DEFAULT_APP_NAME);
      assertEquals("Performance", app.getLabel());
      assertThat(app.getSections()).flatExtracting(QAppSection::getTables).hasSize(WebPerfSchema.TABLES.size());
      for(QAppSection section : app.getSections())
      {
         assertThat(section.getProcesses() == null ? List.of() : section.getProcesses()).as("no process in the nav").isEmpty();
      }

      for(String base : List.of(WebPerfSchema.LAB_RUN, WebPerfSchema.LAB_FINDING, WebPerfSchema.LAB_REPORT, WebPerfSchema.FIELD_SNAPSHOT,
         WebPerfSchema.CHANGE_EVENT, WebPerfSchema.COMPARISON, WebPerfSchema.REGRESSION))
      {
         QTableMetaData table = qInstance.getTable("web_performance_" + base);
         assertFalse(table.isCapabilityEnabled(qInstance.getBackend(WebPerfTestSupport.BACKEND), Capability.TABLE_INSERT), base);
         assertTrue(table.getFields().values().stream().noneMatch(f -> Boolean.TRUE.equals(f.getIsEditable())), base);
      }
      for(String base : List.of(WebPerfSchema.TARGET, WebPerfSchema.BUDGET, WebPerfSchema.REGRESSION))
      {
         assertEquals(AuditLevel.FIELD, qInstance.getTable("web_performance_" + base).getAuditRules().getAuditLevel(), base);
      }
      assertEquals(AuditLevel.NONE, qInstance.getTable("web_performance_lab_run").getAuditRules().getAuditLevel());
      assertTrue(qInstance.getTable("web_performance_target").getField("enabled").getIsEditable());
      assertFalse(qInstance.getTable("web_performance_target").getField("latestMobilePerformance").getIsEditable());
      assertEquals("web_performance", qInstance.getTable("web_performance_target").getPermissionRules().getPermissionBaseName());
   }



   /*******************************************************************************
    ** Every enum field resolves to an install-scoped ENUM with stable stored IDs.
    *******************************************************************************/
   @Test
   void testEnumFields() throws Exception
   {
      QInstance instance = WebPerfTestSupport.newInstance();
      new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config().withTableNamePrefix("perf_")).produce(instance);
      Map<String, List<String>> expected = Map.ofEntries(
         Map.entry("tier", List.of("KEY", "STANDARD")),
         Map.entry("target_source", List.of("SITEMAP", "MANUAL", "CONTENT")),
         Map.entry("strategy", List.of("MOBILE", "DESKTOP")),
         Map.entry("strategy_or_form_factor", List.of("MOBILE", "DESKTOP", "PHONE", "TABLET", "ALL")),
         Map.entry("field_source", List.of("CRUX_API", "CRUX_HISTORY", "PSI")),
         Map.entry("field_scope", List.of("URL", "ORIGIN")),
         Map.entry("budget_scope", List.of("SITE", "TIER", "TARGET", "CONTENT_TABLE", "PATH_PREFIX")),
         Map.entry("measurement_source", List.of("LAB", "RUM", "CRUX")),
         Map.entry("comparison_source", List.of("LAB", "RUM")),
         Map.entry("comparison_scope", List.of("KEY", "TARGET", "SITE")),
         Map.entry("change_source", List.of("PROBE", "WEBHOOK", "QBIT_CDN", "USER")),
         Map.entry("metric", List.of("LCP", "INP", "CLS", "FCP", "TTFB", "TBT", "PERFORMANCE_SCORE", "ACCESSIBILITY_SCORE", "BEST_PRACTICES_SCORE", "SEO_SCORE")),
         Map.entry("comparator", List.of("<=", ">=")),
         Map.entry("severity", List.of("WARNING", "CRITICAL")));
      int count = 0;
      for(QTableMetaData table : instance.getTables().values())
      {
         for(QFieldMetaData field : table.getFields().values())
         {
            if(List.of("tier", "scope", "source", "strategy", "metric", "comparator", "severity").contains(field.getName()))
            {
               count++;
               assertThat(field.getPossibleValueSourceName()).as(table.getName() + "." + field.getName()).startsWith("perf_");
               QPossibleValueSource source = instance.getPossibleValueSource(field.getPossibleValueSourceName());
               assertEquals(QPossibleValueSourceType.ENUM, source.getType());
               assertThat(source.getEnumValues()).extracting(v -> (String) v.getId()).containsExactlyElementsOf(expected.get(source.getName().substring(5)));
               assertThat(source.getEnumValues()).allMatch(v -> v.getLabel() != null && !v.getLabel().isBlank());
            }
         }
      }
      assertEquals(21, count);
      assertEquals("Key page", instance.getPossibleValueSource("perf_tier").getEnumValues().get(0).getLabel());
      assertTrue(instance.getTable("perf_target").getField("url").getIsEditable());
      assertTrue(instance.getTable("perf_target").isCapabilityEnabled(instance.getBackend(WebPerfTestSupport.BACKEND), Capability.TABLE_INSERT));
   }



   /*******************************************************************************
    ** The app nests under a host parent app (kof22: CMS, D5).
    *******************************************************************************/
   @Test
   void testNestsUnderTheParentApp() throws Exception
   {
      QInstance qInstance = WebPerfTestSupport.newInstance();
      qInstance.addApp(new QAppMetaData().withName("cms").withLabel("CMS"));
      new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config().withParentAppName("cms")).produce(qInstance);
      new QInstanceValidator().validate(qInstance);
      assertThat(qInstance.getApp("cms").getChildren()).extracting(c -> c.getName()).contains(WebPerfQBitConfig.DEFAULT_APP_NAME);
   }



   /*******************************************************************************
    ** A namespace becomes the prefix, so two installs never collide; a second
    ** install with the same names is refused.
    *******************************************************************************/
   @Test
   void testNamespacesAndDuplicates() throws Exception
   {
      QInstance qInstance = WebPerfTestSupport.newInstance();
      new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config()).produce(qInstance);
      new WebPerfQBitProducer().withNamespace("shop").withQBitConfig(WebPerfTestSupport.config().withAppName("shopPerformance")).produce(qInstance);
      assertNotNull(qInstance.getTable("shop_target"));
      assertNotNull(qInstance.getTable("web_performance_target"));

      assertThatThrownBy(() -> new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config()).produce(qInstance))
         .isInstanceOf(QException.class).hasMessageContaining("already exists");
   }



   /*******************************************************************************
    ** Config validation names every problem.
    *******************************************************************************/
   @Test
   void testConfigValidation()
   {
      QInstance qInstance = WebPerfTestSupport.newInstance();
      assertThatThrownBy(() -> new WebPerfQBitProducer().produce(qInstance)).isInstanceOf(QException.class).hasMessageContaining("no config");

      WebPerfQBitConfig bad = new WebPerfQBitConfig()
         .withBackendName("nope")
         .withTableNamePrefix("Bad-")
         .withSiteBaseUrl("www.example.com")
         .withStrategies(List.of("TABLET"))
         .withCategories(List.of("PWA"))
         .withMaxRunsPerDay(30_000)
         .withConcurrency(0)
         .withTimeZone("Mars/Base")
         .withParentAppName("missing")
         .withContentTables(List.of("page"));
      assertThatThrownBy(() -> new WebPerfQBitProducer().withQBitConfig(bad).produce(qInstance))
         .isInstanceOf(QException.class)
         .hasMessageContaining("backend nope is not defined")
         .hasMessageContaining("tableNamePrefix")
         .hasMessageContaining("siteBaseUrl")
         .hasMessageContaining("strategies")
         .hasMessageContaining("categories")
         .hasMessageContaining("maxRunsPerDay")
         .hasMessageContaining("concurrency")
         .hasMessageContaining("timeZone")
         .hasMessageContaining("parent app missing")
         .hasMessageContaining("content table page");
   }



   /*******************************************************************************
    ** Defaults follow the owner decisions; the key is never printed.
    *******************************************************************************/
   @Test
   void testDefaultsAndSecretRedaction()
   {
      WebPerfQBitConfig config = WebPerfTestSupport.config().withGoogleApiKey("AIzaSecretValue");
      assertEquals(500, config.getMaxRunsPerDay());
      assertEquals(List.of("MOBILE", "DESKTOP"), config.getStrategies());
      assertEquals(List.of("PERFORMANCE", "ACCESSIBILITY", "BEST_PRACTICES", "SEO"), config.getCategories());
      assertEquals(0, config.getReportRetentionDays(), "reports kept forever (D8)");
      assertTrue(config.isOn(WebPerfFeature.ALERTS));
      assertFalse(config.isOn(WebPerfFeature.ALERT_METRICS), "Prometheus gauges are optional and off by default (D9)");
      assertTrue(config.hasGoogleApiKey());
      assertThat(config.toString()).doesNotContain("AIzaSecretValue").contains("<redacted>");
      assertThat(WebPerfTestSupport.config().toString()).contains("googleApiKey=<none>");
      assertEquals("webPerformanceTestPageSpeed", config.qualify("TestPageSpeed"));

      config.withFeatures(EnumSet.of(WebPerfFeature.LAB));
      assertTrue(config.isOn(WebPerfFeature.LAB));
      assertFalse(config.isOn(WebPerfFeature.ALERTS));
      config.withFeatures(java.util.Set.of());
      assertFalse(config.isOn(WebPerfFeature.LAB));
   }
}
