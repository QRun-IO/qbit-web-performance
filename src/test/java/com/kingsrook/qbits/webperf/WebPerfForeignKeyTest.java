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


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Referential integrity on PostgreSQL (the production database), with the
 ** changelog run by Liquibase as a host runs it:
 **
 ** - every table possible-value field that names one of the QBit's tables,
 **   and every join, has a database foreign key (or an exemption with a
 **   reason), so a new reference cannot be added without its constraint;
 ** - rows whose parent is missing when the constraints are added are recorded
 **   in qqq_fk_quarantine and repaired (nulled, or deleted with a full copy),
 **   so the upgrade never fails on existing data;
 ** - the foreign key changesets are no-ops on a database that already has the
 **   constraints (a host that added them itself).
 **
 ** Skipped when Docker is not available.
 *******************************************************************************/
@Testcontainers(disabledWithoutDocker = true)
class WebPerfForeignKeyTest
{
   private static final String PREFIX = "web_performance_";

   /////////////////////////////////////////////////////////////////////////////
   // references that are deliberately not foreign keys: "child.column" ->    //
   // reason. None today (content_table + content_id is polymorphic, and has  //
   // no possible-value source).                                              //
   /////////////////////////////////////////////////////////////////////////////
   private static final Map<String, String> EXEMPT = Map.of();

   /////////////////////////////////////////////////////////////////////////////
   // the changesets before the foreign keys (1-11)                           //
   /////////////////////////////////////////////////////////////////////////////
   private static final int CHANGESETS_BEFORE_FOREIGN_KEYS = 11;

   @Container
   private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");



   /*******************************************************************************
    ** Orphans are quarantined, every reference gets its constraint, and a
    ** re-run of the foreign key changesets changes nothing.
    *******************************************************************************/
   @Test
   void testEveryReferenceHasAForeignKey() throws Exception
   {
      try(Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
         Statement statement = connection.createStatement())
      {
         statement.execute("DROP SCHEMA public CASCADE");
         statement.execute("CREATE SCHEMA public");

         ////////////////////////////////////////////////////////////////////////
         // an existing install with rows whose parents are gone               //
         ////////////////////////////////////////////////////////////////////////
         migrate(connection, CHANGESETS_BEFORE_FOREIGN_KEYS);
         statement.execute("INSERT INTO web_performance_target (id, url) VALUES (1, 'https://www.example.com/')");
         statement.execute("INSERT INTO web_performance_lab_run (id, target_id) VALUES (1, 1), (2, 404)");
         statement.execute("INSERT INTO web_performance_lab_finding (id, run_id, target_id) VALUES (1, 1, 1), (2, 404, 1)");
         statement.execute("INSERT INTO web_performance_lab_report (id, run_id) VALUES (1, 404)");
         statement.execute("INSERT INTO web_performance_regression (id, target_id, budget_id, first_run_id) VALUES (1, 1, 404, 404)");

         migrate(connection, null);

         ////////////////////////////////////////////////////////////////////////
         // every reference the meta-data declares has its constraint          //
         ////////////////////////////////////////////////////////////////////////
         Map<String, String> foreignKeys = foreignKeys(statement);
         List<String>        missing     = new ArrayList<>();
         for(String reference : references())
         {
            if(!foreignKeys.containsKey(reference) && !EXEMPT.containsKey(reference))
            {
               missing.add(reference);
            }
         }
         assertThat(missing).as("references without a foreign key (add one to the changelog, or an exemption with a reason)").isEmpty();
         assertThat(foreignKeys).containsEntry("web_performance_lab_finding.run_id", "web_performance_lab_run CASCADE")
            .containsEntry("web_performance_lab_run.target_id", "web_performance_target RESTRICT")
            .containsEntry("web_performance_regression.cause_change_event_id", "web_performance_change_event SET NULL");

         ////////////////////////////////////////////////////////////////////////
         // the orphans were recorded, then nulled or deleted                  //
         ////////////////////////////////////////////////////////////////////////
         assertThat(rows(statement, "SELECT table_name || '.' || column_name || ' ' || row_id || '->' || missing_parent_id || ' ' || action || ' ' || (row_data IS NOT NULL) FROM qqq_fk_quarantine"))
            .containsExactlyInAnyOrder(
               "web_performance_lab_run.target_id 2->404 NULLED false",
               "web_performance_lab_finding.run_id 2->404 DELETED true",
               "web_performance_lab_report.run_id 1->404 DELETED true",
               "web_performance_regression.budget_id 1->404 NULLED false",
               "web_performance_regression.first_run_id 1->404 NULLED false");
         assertThat(rows(statement, "SELECT id || ' ' || COALESCE(CAST(target_id AS TEXT), '-') FROM web_performance_lab_run")).containsExactlyInAnyOrder("1 1", "2 -");
         assertThat(rows(statement, "SELECT CAST(id AS TEXT) FROM web_performance_lab_finding")).containsExactly("1");
         assertThat(rows(statement, "SELECT CAST(id AS TEXT) FROM web_performance_lab_report")).isEmpty();

         ////////////////////////////////////////////////////////////////////////
         // a host that already has the constraints: the changesets are marked //
         // ran and change nothing                                             //
         ////////////////////////////////////////////////////////////////////////
         statement.execute("DELETE FROM databasechangelog WHERE CAST(SUBSTRING(id FROM '[0-9]+$') AS INTEGER) > " + CHANGESETS_BEFORE_FOREIGN_KEYS);
         migrate(connection, null);
         assertThat(foreignKeys(statement)).isEqualTo(foreignKeys);
         assertThat(rows(statement, "SELECT CAST(COUNT(*) AS TEXT) FROM qqq_fk_quarantine")).containsExactly("5");
      }
   }



   /*******************************************************************************
    ** Run the changelog (all of it, or the first n changesets).
    *******************************************************************************/
   private static void migrate(Connection connection, Integer count) throws Exception
   {
      Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      Liquibase liquibase = new Liquibase(WebPerfSchema.CHANGELOG, new ClassLoaderResourceAccessor(), database);
      liquibase.setChangeLogParameter(WebPerfSchema.CHANGELOG_PREFIX_PROPERTY, PREFIX);
      if(count == null)
      {
         liquibase.update(new Contexts(), new LabelExpression());
      }
      else
      {
         liquibase.update(count, new Contexts(), new LabelExpression());
      }
   }



   /*******************************************************************************
    ** "child.column" for every table possible-value field and join of the QBit.
    *******************************************************************************/
   private static List<String> references() throws Exception
   {
      QInstance qInstance = WebPerfTestSupport.newInstance();
      new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config().withTableNamePrefix(PREFIX)).produce(qInstance);

      List<String> references = new ArrayList<>();
      for(QTableMetaData table : qInstance.getTables().values())
      {
         for(QFieldMetaData field : table.getFields().values())
         {
            if(field.getPossibleValueSourceName() != null && qInstance.getTable(field.getPossibleValueSourceName()) != null)
            {
               references.add(table.getName() + "." + field.getBackendName());
            }
         }
      }
      for(QJoinMetaData join : qInstance.getJoins().values())
      {
         QTableMetaData right = qInstance.getTable(join.getRightTable());
         join.getJoinOns().forEach(on -> references.add(join.getRightTable() + "." + right.getField(on.getRightField()).getBackendName()));
      }
      assertThat(references).as("the QBit's references").hasSizeGreaterThanOrEqualTo(12);
      return (references);
   }



   /*******************************************************************************
    ** "child.column" -> "parent RULE" for every foreign key in the database.
    *******************************************************************************/
   private static Map<String, String> foreignKeys(Statement statement) throws Exception
   {
      Map<String, String> foreignKeys = new HashMap<>();
      try(ResultSet resultSet = statement.executeQuery("""
         SELECT tc.table_name || '.' || kcu.column_name, ccu.table_name || ' ' || rc.delete_rule
         FROM information_schema.table_constraints tc
         JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name
         JOIN information_schema.referential_constraints rc ON rc.constraint_name = tc.constraint_name
         JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name
         WHERE tc.constraint_type = 'FOREIGN KEY'"""))
      {
         while(resultSet.next())
         {
            foreignKeys.put(resultSet.getString(1), resultSet.getString(2));
         }
      }
      return (foreignKeys);
   }



   /*******************************************************************************
    ** The first column of each row of a query, as text.
    *******************************************************************************/
   private static List<String> rows(Statement statement, String sql) throws Exception
   {
      List<String> rows = new ArrayList<>();
      try(ResultSet resultSet = statement.executeQuery(sql))
      {
         while(resultSet.next())
         {
            rows.add(resultSet.getString(1));
         }
      }
      return (rows);
   }
}
