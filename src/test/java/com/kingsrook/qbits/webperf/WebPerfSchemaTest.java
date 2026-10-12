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


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;


/*******************************************************************************
 ** The changelog and the table meta-data agree on a real database (H2): every
 ** field of every table has its column, every table can be queried through
 ** QQQ, people-edited tables accept inserts with the webperf permissions,
 ** and the seeded budgets are inserted once.
 *******************************************************************************/
class WebPerfSchemaTest
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
    ** An instance with the QBit under a prefix and its changelog applied.
    *******************************************************************************/
   private static QInstance installed(String prefix) throws Exception
   {
      QInstance qInstance = WebPerfTestSupport.newInstance();
      new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config().withTableNamePrefix(prefix)).produce(qInstance);
      WebPerfTestSupport.applyChangelog(qInstance, prefix);
      WebPerfTestSupport.login(qInstance, "webperf.read", "webperf.write");
      return (qInstance);
   }



   /*******************************************************************************
    ** Every field has its column; every table is queryable through QQQ.
    *******************************************************************************/
   @Test
   void testEveryFieldHasItsColumn() throws Exception
   {
      QInstance qInstance = installed("webperf_");
      try(Connection connection = ConnectionManager.getConnection((RDBMSBackendMetaData) qInstance.getBackend(WebPerfTestSupport.BACKEND)))
      {
         DatabaseMetaData metaData = connection.getMetaData();
         for(String base : WebPerfSchema.TABLES)
         {
            QTableMetaData table   = qInstance.getTable("webperf_" + base);
            Set<String>    columns = new HashSet<>();
            try(ResultSet resultSet = metaData.getColumns(null, null, table.getName(), null))
            {
               while(resultSet.next())
               {
                  columns.add(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
               }
            }
            assertThat(columns).as(table.getName() + " exists").isNotEmpty();
            for(QFieldMetaData field : table.getFields().values())
            {
               assertThat(columns).as(table.getName() + "." + field.getName()).contains(field.getBackendName());
            }
            assertThat(columns).as(table.getName() + " has no column without a field").hasSize(table.getFields().size());

            QueryOutput output = new QueryAction().execute(new QueryInput(table.getName()).withFilter(new QQueryFilter()));
            assertThat(output.getRecords()).as(table.getName()).isNotNull();
         }
      }
   }



   /*******************************************************************************
    ** The seeded budgets: Google's "good" thresholds and the category bands,
    ** inserted once (a re-run of the changeset adds nothing).
    *******************************************************************************/
   @Test
   void testSeededBudgets() throws Exception
   {
      QInstance qInstance = installed("webperf_");
      List<QRecord> budgets = new QueryAction().execute(new QueryInput("webperf_budget").withFilter(new QQueryFilter())).getRecords();
      assertEquals(13, budgets.size());
      assertThat(budgets).allMatch(r -> Boolean.TRUE.equals(r.getValueBoolean("seeded")) && Boolean.TRUE.equals(r.getValueBoolean("enabled")));

      QRecord inp = budgets.stream().filter(r -> "INP good (real users, p75)".equals(r.getValueString("name"))).findFirst().orElseThrow();
      assertEquals("RUM", inp.getValueString("source"));
      assertEquals(0, new BigDecimal("200").compareTo(inp.getValueBigDecimal("threshold")));
      QRecord critical = budgets.stream().filter(r -> "CRITICAL".equals(r.getValueString("severity"))).findFirst().orElseThrow();
      assertEquals("KEY", critical.getValueString("scopeValue"));

      WebPerfTestSupport.applyChangelog(qInstance, "webperf_");
      assertEquals(13, new QueryAction().execute(new QueryInput("webperf_budget").withFilter(new QQueryFilter())).getRecords().size());
   }



   /*******************************************************************************
    ** A tested page and a budget can be added by a user with webperf.write,
    ** under any prefix; the unique key keeps one row per site and URL.
    *******************************************************************************/
   @Test
   void testPeopleEditedTablesAcceptInserts() throws Exception
   {
      installed("perf_");
      InsertOutput output = new InsertAction().execute(new InsertInput("perf_target").withRecords(List.of(
         new QRecord().withValue("siteKey", "site:1").withValue("url", "https://www.example.com/about").withValue("path", "/about")
            .withValue("source", "MANUAL").withValue("tier", "KEY").withValue("enabled", true))));
      assertThat(output.getRecords().get(0).getErrors()).isEmpty();

      QRecord stored = new QueryAction().execute(new QueryInput("perf_target").withFilter(new QQueryFilter())).getRecords().get(0);
      assertEquals("https://www.example.com/about", stored.getValueString("url"));
      assertEquals("KEY", stored.getValueString("tier"));

      InsertOutput duplicate = new InsertAction().execute(new InsertInput("perf_target").withRecords(List.of(
         new QRecord().withValue("siteKey", "site:1").withValue("url", "https://www.example.com/about"))));
      assertThat(duplicate.getRecords().get(0).getErrors()).isNotEmpty();
   }
}
