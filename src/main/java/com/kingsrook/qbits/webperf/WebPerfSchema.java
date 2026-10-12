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


import java.util.List;


/*******************************************************************************
 ** The QBit's table base names (design §4.2). The SQL and QQQ table name is
 ** the configured prefix plus the base name, e.g. "webperf_target".
 *******************************************************************************/
public final class WebPerfSchema
{
   public static final String TARGET          = "target";
   public static final String LAB_RUN         = "lab_run";
   public static final String LAB_FINDING     = "lab_finding";
   public static final String LAB_REPORT      = "lab_report";
   public static final String FIELD_SNAPSHOT  = "field_snapshot";
   public static final String BUDGET          = "budget";
   public static final String REGRESSION      = "regression";
   public static final String CHANGE_EVENT    = "change_event";
   public static final String COMPARISON      = "comparison";

   /** Every table, in app order. */
   public static final List<String> TABLES = List.of(TARGET, LAB_RUN, LAB_FINDING, REGRESSION, BUDGET, CHANGE_EVENT, COMPARISON,
      FIELD_SNAPSHOT, LAB_REPORT);

   /** Classpath location of the host-included Liquibase changelog. */
   public static final String CHANGELOG = "db/liquibase/qbit-web-performance/webperf.changelog.sql";

   /** The changelog property holding the table prefix (must equal the config's prefix). */
   public static final String CHANGELOG_PREFIX_PROPERTY = "webperf.tablePrefix";



   /*******************************************************************************
    ** Constants only.
    *******************************************************************************/
   private WebPerfSchema()
   {
   }
}
