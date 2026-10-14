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


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The shipped changelog, run through real Liquibase on H2 (PostgreSQL mode):
 ** it creates the nine tables, changeset 10 seeds the reference budgets, a
 ** second update runs nothing, and the seed statement run again inserts nothing
 ** and keeps an administrator's edit.
 *******************************************************************************/
class WebPerfDefaultsChangelogTest
{
   private static final String PREFIX = "web_performance_";



   /*******************************************************************************
    **
    *******************************************************************************/
   @Test
   void seededBudgetsThroughRealLiquibase_idempotent_andKeepEdits() throws Exception
   {
      try(Connection connection = connect())
      {
         update();
         assertThat(count(connection, "SELECT COUNT(*) FROM " + PREFIX + "budget")).isEqualTo(13);
         assertThat(count(connection, "SELECT COUNT(*) FROM " + PREFIX + "budget WHERE seeded = TRUE AND enabled = TRUE")).isEqualTo(13);
         for(String base : WebPerfSchema.TABLES)
         {
            assertThat(count(connection, "SELECT COUNT(*) FROM " + PREFIX + base)).as(base + " is queryable").isGreaterThanOrEqualTo(0);
         }
         int changesets = count(connection, "SELECT COUNT(*) FROM DATABASECHANGELOG");

         ////////////////////////////////////////////////////////////////
         // a second update has nothing to run                          //
         ////////////////////////////////////////////////////////////////
         update();
         assertThat(count(connection, "SELECT COUNT(*) FROM DATABASECHANGELOG")).isEqualTo(changesets);
         assertThat(count(connection, "SELECT COUNT(*) FROM " + PREFIX + "budget")).isEqualTo(13);

         ////////////////////////////////////////////////////////////////////////////
         // an admin edits a seed; the seed statement (changeset 10) is run again: //
         // nothing is inserted and the edit survives                              //
         ////////////////////////////////////////////////////////////////////////////
         try(Statement statement = connection.createStatement())
         {
            statement.execute("UPDATE " + PREFIX + "budget SET threshold = 3000 WHERE name = 'LCP good (lab, mobile)'");
            statement.execute(seedStatement());
         }
         assertThat(count(connection, "SELECT COUNT(*) FROM " + PREFIX + "budget")).isEqualTo(13);
         assertThat(count(connection, "SELECT COUNT(*) FROM " + PREFIX + "budget WHERE name = 'LCP good (lab, mobile)' AND threshold = 3000")).isEqualTo(1);
      }
   }



   /*******************************************************************************
    ** The INSERT of changeset 10, with the prefix filled in.
    *******************************************************************************/
   private static String seedStatement() throws Exception
   {
      String script;
      try(java.io.InputStream in = WebPerfDefaultsChangelogTest.class.getClassLoader().getResourceAsStream(WebPerfSchema.CHANGELOG))
      {
         script = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      String seed = script.substring(script.indexOf("-- changeset qbit-web-performance:10"), script.indexOf("-- changeset qbit-web-performance:11"));
      seed = seed.substring(seed.indexOf("INSERT INTO")).trim().replaceAll(";$", "");
      return (seed.replace("${" + WebPerfSchema.CHANGELOG_PREFIX_PROPERTY + "}", PREFIX));
   }



   /*******************************************************************************
    ** A connection to the shared in-memory database.
    *******************************************************************************/
   private static Connection connect() throws Exception
   {
      return (DriverManager.getConnection("jdbc:h2:mem:wpdefaults;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
   }



   /*******************************************************************************
    ** One real Liquibase update on its own connection (Liquibase closes it).
    *******************************************************************************/
   private static void update() throws Exception
   {
      Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect()));
      try(Liquibase liquibase = new Liquibase(WebPerfSchema.CHANGELOG, new ClassLoaderResourceAccessor(), database))
      {
         liquibase.setChangeLogParameter(WebPerfSchema.CHANGELOG_PREFIX_PROPERTY, PREFIX);
         liquibase.update("");
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static int count(Connection connection, String sql) throws Exception
   {
      try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
      {
         resultSet.next();
         return (resultSet.getInt(1));
      }
   }

}
