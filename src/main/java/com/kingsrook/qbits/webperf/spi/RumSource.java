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
import java.time.LocalDate;
import java.util.List;


/*******************************************************************************
 ** Reads real-user Core Web Vitals aggregates (design §7, D4). Vitals are
 ** collected and stored by the analytics module (analytics_vital and
 ** analytics_vital_stat, qbit-cms-analytics); this QBit only presents them.
 *******************************************************************************/
public interface RumSource extends Serializable
{
   /*******************************************************************************
    ** One aggregate.
    **
    ** @param metric           LCP, INP, CLS, FCP or TTFB
    ** @param windowDays       1 or 28
    ** @param dimension        total, path, device, country or connection
    ** @param dimensionValue   the value ("" for total)
    ** @param samples          how many measurements
    ** @param p75              the 75th percentile (ms; CLS unitless), null without samples
    ** @param good             good measurements
    ** @param needsImprovement needs-improvement measurements
    ** @param poor             poor measurements
    *******************************************************************************/
   record VitalAggregate(String metric, int windowDays, String dimension, String dimensionValue, int samples, Double p75, int good,
                         int needsImprovement, int poor) implements Serializable
   {
   }



   /*******************************************************************************
    ** The aggregates of a day for a dimension.
    **
    ** @param day        the day
    ** @param windowDays 1 or 28
    ** @param dimension  the dimension
    ** @return the aggregates (empty when nothing was measured)
    ** @throws Exception on a read failure
    *******************************************************************************/
   List<VitalAggregate> aggregates(LocalDate day, int windowDays, String dimension) throws Exception;
}
