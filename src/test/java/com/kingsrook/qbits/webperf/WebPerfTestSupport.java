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


import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;


/*******************************************************************************
 ** Test helpers: a fresh in-memory H2 backend per test, the QBit's changelog
 ** applied the way a host's Liquibase would (changeset by changeset, with the
 ** prefix property substituted), and a session with the webperf permissions.
 *******************************************************************************/
public final class WebPerfTestSupport
{
   public static final String BACKEND = "rdbms";



   /*******************************************************************************
    ** Helpers only.
    *******************************************************************************/
   private WebPerfTestSupport()
   {
   }



   /*******************************************************************************
    ** An instance with a fresh H2 backend and mock authentication.
    **
    ** @return the instance
    *******************************************************************************/
   public static QInstance newInstance()
   {
      RDBMSBackendMetaData backend = new RDBMSBackendMetaData()
         .withName(BACKEND)
         .withVendor("h2")
         .withHostName("mem")
         .withDatabaseName("web_performance_" + UUID.randomUUID().toString().replace("-", ""))
         .withUsername("sa");
      backend.setJdbcUrl("jdbc:h2:mem:" + backend.getDatabaseName() + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1");

      QInstance qInstance = new QInstance();
      qInstance.addBackend(backend);
      qInstance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(QAuthenticationType.MOCK));
      return (qInstance);
   }



   /*******************************************************************************
    ** A minimal valid config for the instance's backend.
    **
    ** @return the config
    *******************************************************************************/
   public static WebPerfQBitConfig config()
   {
      return (new WebPerfQBitConfig()
         .withBackendName(BACKEND)
         .withSiteBaseUrl("https://www.example.com"));
   }



   /*******************************************************************************
    ** Start a context whose session has the given permissions.
    **
    ** @param qInstance   the instance
    ** @param permissions the permissions
    ** @throws Exception on an invalid instance
    *******************************************************************************/
   public static void login(QInstance qInstance, String... permissions) throws Exception
   {
      QContext.init(qInstance, new QSession().withPermissions(permissions));
   }



   /*******************************************************************************
    ** Apply the QBit's changelog with a prefix: each changeset's statements in
    ** order, comment lines skipped, ${web_performance.tablePrefix} substituted.
    **
    ** @param qInstance the instance
    ** @param prefix    the table prefix
    ** @throws Exception on failure
    *******************************************************************************/
   public static void applyChangelog(QInstance qInstance, String prefix) throws Exception
   {
      String script;
      try(InputStream in = WebPerfTestSupport.class.getClassLoader().getResourceAsStream(WebPerfSchema.CHANGELOG))
      {
         script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      script = script.replace("${" + WebPerfSchema.CHANGELOG_PREFIX_PROPERTY + "}", prefix);

      List<String>  statements = new ArrayList<>();
      StringBuilder current    = new StringBuilder();
      boolean       skip       = false;
      for(String line : script.split("\n"))
      {
         //////////////////////////////////////////////////////////////////////
         // a changeset limited to other databases (the dbms:postgresql      //
         // orphan cleanup) is skipped, as Liquibase skips it on H2          //
         //////////////////////////////////////////////////////////////////////
         if(line.startsWith("-- changeset "))
         {
            skip = line.contains(" dbms:") && !line.contains("h2");
         }
         if(skip || line.trim().startsWith("--"))
         {
            continue;
         }
         current.append(line).append('\n');
         if(line.trim().endsWith(";"))
         {
            statements.add(current.toString().trim().replaceAll(";$", ""));
            current.setLength(0);
         }
      }

      try(Connection connection = ConnectionManager.getConnection((RDBMSBackendMetaData) qInstance.getBackend(BACKEND));
         Statement statement = connection.createStatement())
      {
         //////////////////////////////////////////////////////////////////////
         // the RDBMS module pools connections per backend name, so the in-  //
         // memory database outlives one test: start every test from empty   //
         //////////////////////////////////////////////////////////////////////
         statement.execute("DROP ALL OBJECTS");
         for(String sql : statements)
         {
            statement.execute(sql);
         }
      }
   }
}
