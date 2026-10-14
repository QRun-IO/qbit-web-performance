/*
 * Copyright 2026 Kingsrook, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.webperf;


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.kingsrook.qbits.webperf.metadata.WebPerfHelp;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.QHelpContent;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Guardrail (owner requirement 2026-10-14: "all tables should be populated and
 ** the qbit should be fully using them"). For every web_performance_* table:
 ** - runtime code (not just its metadata or the home widgets) reads or writes
 **   it, so no table exists only on paper;
 ** - it is either seeded by the shipped changelog or listed in STARTS_EMPTY
 **   with why it starts empty;
 ** - it has bundle help that says what it is for and how its rows get created.
 ** A new table fails this test until it is wired, seeded or explained.
 *******************************************************************************/
class WebPerfTablesGuardrailTest
{
   private static final Path MAIN = Path.of("src", "main", "java", "com", "kingsrook", "qbits", "webperf");

   /*******************************************************************************
    ** Tables the shipped changelog seeds (WebPerfDefaultsChangelogTest proves it).
    *******************************************************************************/
   static final Map<String, String> SEEDED = Map.of(
      "budget", "changeset 10: Google's good thresholds and the Lighthouse category bands");

   /*******************************************************************************
    ** Tables that legitimately start empty, and where their rows come from.
    *******************************************************************************/
   static final Map<String, String> STARTS_EMPTY = Map.of(
      "target", "synced from the sitemap (Sync pages from sitemap) or added by hand; the page list is host content",
      "lab_run", "runtime: every queued or finished Lighthouse test",
      "lab_finding", "runtime: findings of each successful lab run",
      "lab_report", "runtime: the full response of each successful lab run",
      "field_snapshot", "runtime: Google field data checks",
      "regression", "runtime: a budget is broken or a score drifts (empty is healthy)",
      "change_event", "runtime: a deploy or content change is detected (needs a probe or source)",
      "comparison", "runtime: Compare before and after on a change event");



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** Every table is used by runtime code.
    *******************************************************************************/
   @Test
   void everyTableIsUsedAtRuntime() throws Exception
   {
      List<String> runtimeSource;
      try(Stream<Path> files = Files.walk(MAIN))
      {
         runtimeSource = files.filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.startsWith(MAIN.resolve("metadata")) && !p.startsWith(MAIN.resolve("widgets")))
            .map(WebPerfTablesGuardrailTest::read).toList();
      }
      for(String base : WebPerfSchema.TABLES)
      {
         Pattern use = Pattern.compile("\\{" + base + "\\}|WebPerfSchema\\." + base.toUpperCase(Locale.ROOT) + "\\b");
         assertThat(runtimeSource.stream().anyMatch(source -> use.matcher(source).find()))
            .as(base + " must be read or written by runtime code, not only declared").isTrue();
      }
   }



   /*******************************************************************************
    ** Every table is seeded or explained.
    *******************************************************************************/
   @Test
   void everyTableIsSeededOrExplained() throws Exception
   {
      String changelog = Files.readString(Path.of("src", "main", "resources", WebPerfSchema.CHANGELOG), StandardCharsets.UTF_8);
      for(String base : WebPerfSchema.TABLES)
      {
         boolean seeded = SEEDED.containsKey(base);
         assertThat(seeded || STARTS_EMPTY.containsKey(base)).as(base + " must be seeded or listed in STARTS_EMPTY").isTrue();
         assertThat(seeded && STARTS_EMPTY.containsKey(base)).as(base + " cannot be both").isFalse();
         assertThat(changelog.contains("INSERT INTO ${web_performance.tablePrefix}" + base + " ")).as(base + " seeded iff the changelog inserts it").isEqualTo(seeded);
      }
      assertThat(WebPerfSchema.TABLES).containsAll(SEEDED.keySet()).containsAll(STARTS_EMPTY.keySet());
   }



   /*******************************************************************************
    ** Every table and the app have bundle help that says how rows get created.
    *******************************************************************************/
   @Test
   void everyTableHasEmptyStateHelp() throws Exception
   {
      QInstance qInstance = WebPerfTestSupport.newInstance();
      new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config()).produce(qInstance);
      WebPerfQBitConfig config = WebPerfTestSupport.config();

      assertThat(WebPerfHelp.entries()).allSatisfy((key, entry) -> assertThat(entry.path("summary").asText().length()).as(key).isLessThanOrEqualTo(120));
      for(String base : WebPerfSchema.TABLES)
      {
         QTableMetaData     table = qInstance.getTable(config.applyPrefix(base));
         List<QHelpContent> help  = table.getHelpContent() == null ? null : table.getHelpContent().get(WebPerfHelp.SLOT);
         assertThat(help).as(base + " table help").isNotEmpty();
         assertThat(help.get(0).getContent()).as(base + " help says how rows get created").contains("How rows get created");
      }
      assertThat(qInstance.getApp(config.getAppName()).getHelpContent()).containsKey(WebPerfHelp.SLOT);
      assertThat(WebPerfHelp.entries()).hasSize(WebPerfSchema.TABLES.size() + 1);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String read(Path path)
   {
      try
      {
         return (Files.readString(path, StandardCharsets.UTF_8));
      }
      catch(Exception e)
      {
         throw (new IllegalStateException(e));
      }
   }

}
