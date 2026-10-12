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


/*******************************************************************************
 ** Optional parts of the QBit (design §9). A disabled feature produces no
 ** metadata. The tables always exist, so turning a feature on later needs no
 ** schema change.
 *******************************************************************************/
public enum WebPerfFeature
{
   /** Lighthouse lab runs through a LabRunner (PageSpeed Insights by default). */
   LAB,

   /** Google field data (CrUX API and PSI loadingExperience). */
   FIELD_CRUX,

   /** Real-user Core Web Vitals read through a RumSource (qbit-cms-analytics). */
   RUM_VIEW,

   /** Re-tests on CMS publish events from the ESB. */
   TRIGGERS_ESB,

   /** Re-tests after deploys, detected by DeployProbes. */
   DEPLOY_PROBES,

   /** Before/after comparisons around CDN stage changes (qbit-cdn). */
   CDN_COMPARE,

   /** Raise and resolve regressions through the Alerts qbit's producer SPI when it is installed. */
   ALERTS,

   /** Optional Prometheus gauges (off by default; not the alerting design). */
   ALERT_METRICS;



   /*******************************************************************************
    ** Whether the feature is on by default.
    **
    ** @return true for every feature except ALERT_METRICS
    *******************************************************************************/
   public boolean isDefault()
   {
      return (this != ALERT_METRICS);
   }
}
