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

package com.kingsrook.qbits.webperf.spi;


import java.io.Serializable;
import java.time.Instant;
import java.util.Set;


/*******************************************************************************
 ** Runs one Lighthouse lab test (design §3.2, D2). The default runner, added in
 ** phase P1 once the Google API key exists, calls the PageSpeed Insights API
 ** v5; a self-hosted Lighthouse runner is an optional later adapter. A runner
 ** returns the complete raw response, so the QBit stores it unchanged
 ** (web_performance_lab_report) and parses scores and findings from it.
 *******************************************************************************/
public interface LabRunner extends Serializable
{
   /*******************************************************************************
    ** One test.
    **
    ** @param url        the absolute URL
    ** @param strategy   MOBILE or DESKTOP
    ** @param categories PERFORMANCE, ACCESSIBILITY, BEST_PRACTICES, SEO
    *******************************************************************************/
   record LabRequest(String url, String strategy, Set<String> categories) implements Serializable
   {
   }



   /*******************************************************************************
    ** What came back.
    **
    ** @param httpStatus  the HTTP status of the call
    ** @param rawJson     the complete response body (stored as the report)
    ** @param completedAt when the call finished
    *******************************************************************************/
   record LabResponse(int httpStatus, String rawJson, Instant completedAt) implements Serializable
   {
   }



   /*******************************************************************************
    ** The runner name stored on each run (PSI, LOCAL, ...).
    **
    ** @return the name
    *******************************************************************************/
   String name();



   /*******************************************************************************
    ** Run one test.
    **
    ** @param request the test
    ** @return the response
    ** @throws Exception on a transport failure (the run is retried with backoff)
    *******************************************************************************/
   LabResponse run(LabRequest request) throws Exception;
}
