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

package com.kingsrook.qbits.webperf.lab;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Reads a PageSpeed Insights v5 response (or a bare Lighthouse result) into
 ** the values the QBit stores: category scores (0-100), the lab metrics,
 ** the LCP element, run warnings and errors, the findings worth acting on,
 ** and the Google field data PSI attaches (loadingExperience).
 **
 ** Findings are the audits of the categories that ran which score below
 ** Lighthouse's passing mark (0.9) and are scored at all (not "not
 ** applicable", "manual", "informative" or errored). An audit referenced by
 ** several categories is reported once, under the first. Audit ids are kept
 ** as Lighthouse names them, with the Lighthouse version, because
 ** Lighthouse 13 replaced most performance audits with insight audits.
 *******************************************************************************/
public final class LabResultParser
{
   /** Lighthouse's passing score. */
   public static final double PASSING = 0.9;

   /** Category ids in the response, and the column they fill. */
   public static final Map<String, String> CATEGORIES = Map.of(
      "performance", "PERFORMANCE",
      "accessibility", "ACCESSIBILITY",
      "best-practices", "BEST_PRACTICES",
      "seo", "SEO");

   private static final Set<String> UNSCORED  = Set.of("notApplicable", "manual", "informative", "error");
   private static final int         MAX_ITEMS = 5;
   private static final int         MAX_TEXT  = 300;



   /*******************************************************************************
    ** One finding.
    **
    ** @param category      PERFORMANCE, ACCESSIBILITY, BEST_PRACTICES or SEO
    ** @param auditId       the Lighthouse audit id
    ** @param kind          INSIGHT, OPPORTUNITY, DIAGNOSTIC or FAILED_AUDIT
    ** @param title         the audit title
    ** @param score         the audit score (0-1)
    ** @param displayValue  the audit's display value (may be null)
    ** @param savingsMs     estimated time savings (may be null)
    ** @param savingsBytes  estimated byte savings (may be null)
    ** @param metricSavings the audit's metric savings as JSON (may be null)
    ** @param topItems      up to five URLs or element selectors, as a JSON array (may be null)
    *******************************************************************************/
   public record Finding(String category, String auditId, String kind, String title, Double score, String displayValue, Integer savingsMs,
                         Long savingsBytes, String metricSavings, String topItems)
   {
   }



   /*******************************************************************************
    ** Google field data (CrUX) as PSI reports it for the URL or its origin.
    **
    ** @param scope    URL or ORIGIN
    ** @param key      the URL or origin
    ** @param fallback whether PSI fell back to the origin for a URL request
    ** @param p75LcpMs LCP p75 (ms)
    ** @param p75InpMs INP p75 (ms)
    ** @param p75Cls   CLS p75
    ** @param p75FcpMs FCP p75 (ms)
    ** @param p75TtfbMs TTFB p75 (ms)
    ** @param distributions the per-metric distributions as JSON
    *******************************************************************************/
   public record FieldData(String scope, String key, boolean fallback, Integer p75LcpMs, Integer p75InpMs, Double p75Cls, Integer p75FcpMs,
                           Integer p75TtfbMs, String distributions)
   {
   }



   /*******************************************************************************
    ** Everything read from one response.
    **
    ** @param finalUrl          the URL after redirects
    ** @param lighthouseVersion e.g. 13.0.1
    ** @param chromeUserAgent   the Chrome that ran the test
    ** @param benchmarkIndex    the host's benchmark index
    ** @param scores            category column name to 0-100 score (only categories that ran)
    ** @param fcpMs             First Contentful Paint
    ** @param lcpMs             Largest Contentful Paint
    ** @param tbtMs             Total Blocking Time
    ** @param cls               Cumulative Layout Shift
    ** @param speedIndexMs      Speed Index
    ** @param lcpElement        the LCP element's selector (may be null)
    ** @param runtimeError      "CODE: message" when Lighthouse could not test the page (else null)
    ** @param runWarnings       Lighthouse's run warnings, joined (may be null)
    ** @param findings          the findings
    ** @param fieldData         Google field data PSI attached (may be empty)
    *******************************************************************************/
   public record ParsedLab(String finalUrl, String lighthouseVersion, String chromeUserAgent, Integer benchmarkIndex, Map<String, Integer> scores,
                           Integer fcpMs, Integer lcpMs, Integer tbtMs, Double cls, Integer speedIndexMs, String lcpElement, String runtimeError,
                           String runWarnings, List<Finding> findings, List<FieldData> fieldData)
   {
      /*******************************************************************************
       ** A category score.
       **
       ** @param category PERFORMANCE, ACCESSIBILITY, BEST_PRACTICES or SEO
       ** @return 0-100 or null
       *******************************************************************************/
      public Integer score(String category)
      {
         return (scores.get(category));
      }
   }



   /*******************************************************************************
    ** Read a reported TTFB subpart from Lighthouse's LCP insight, never savings.
    *******************************************************************************/
   public static Integer ttfb(String json)
   {
      JSONObject root = new JSONObject(json);
      JSONObject lhr = root.optJSONObject("lighthouseResult");
      JSONObject audits = (lhr == null ? root : lhr).optJSONObject("audits");
      if(audits == null)
      {
         return (null);
      }
      for(String name : List.of("lcp-phases-insight", "lcp-breakdown-insight"))
      {
         JSONObject audit = audits.optJSONObject(name);
         JSONObject details = audit == null ? null : audit.optJSONObject("details");
         JSONArray items = details == null ? null : details.optJSONArray("items");
         if(items != null)
         {
            for(int i = 0; i < items.length(); i++)
            {
               JSONObject item = items.optJSONObject(i);
               if(item == null)
               {
                  continue;
               }
               JSONArray subparts = item.optJSONArray("items");
               if(subparts == null)
               {
                  subparts = new JSONArray().put(item);
               }
               for(int j = 0; j < subparts.length(); j++)
               {
                  JSONObject part = subparts.optJSONObject(j);
                  if(part != null && "timeToFirstByte".equals(part.optString("subpart")) && part.opt("duration") instanceof Number duration)
                  {
                     return ((int) Math.round(duration.doubleValue()));
                  }
               }
            }
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** Parser only.
    *******************************************************************************/
   private LabResultParser()
   {
   }



   /*******************************************************************************
    ** Parse a response body.
    **
    ** @param json the PSI response, or a bare Lighthouse result
    ** @return the parsed values
    ** @throws IllegalArgumentException when the body has no Lighthouse result
    *******************************************************************************/
   public static ParsedLab parse(String json)
   {
      JSONObject root = new JSONObject(json);
      JSONObject lhr  = root.optJSONObject("lighthouseResult");
      if(lhr == null && root.has("audits") && root.has("categories"))
      {
         lhr = root;
      }
      if(lhr == null)
      {
         throw (new IllegalArgumentException("The response has no Lighthouse result"));
      }

      JSONObject audits     = lhr.optJSONObject("audits", new JSONObject());
      JSONObject categories = lhr.optJSONObject("categories", new JSONObject());

      Map<String, Integer> scores = new LinkedHashMap<>();
      for(Map.Entry<String, String> category : CATEGORIES.entrySet())
      {
         JSONObject object = categories.optJSONObject(category.getKey());
         if(object != null && object.has("score") && !object.isNull("score"))
         {
            scores.put(category.getValue(), (int) Math.round(object.getDouble("score") * 100));
         }
      }

      String runtimeError = null;
      JSONObject error = lhr.optJSONObject("runtimeError");
      if(error != null && error.has("code"))
      {
         runtimeError = truncate(error.optString("code") + ": " + error.optString("message"), 2000);
      }

      JSONArray    warnings     = lhr.optJSONArray("runWarnings");
      List<String> warningTexts = new ArrayList<>();
      for(int i = 0; warnings != null && i < warnings.length(); i++)
      {
         warningTexts.add(String.valueOf(warnings.get(i)));
      }

      JSONObject environment = lhr.optJSONObject("environment", new JSONObject());
      String     finalUrl    = lhr.optString("finalDisplayedUrl", lhr.optString("finalUrl", null));

      return (new ParsedLab(
         finalUrl,
         lhr.optString("lighthouseVersion", null),
         truncate(environment.optString("hostUserAgent", null), MAX_TEXT),
         environment.has("benchmarkIndex") ? (int) Math.round(environment.getDouble("benchmarkIndex")) : null,
         scores,
         millis(audits, "first-contentful-paint"),
         millis(audits, "largest-contentful-paint"),
         millis(audits, "total-blocking-time"),
         numeric(audits, "cumulative-layout-shift") == null ? null : Math.round(numeric(audits, "cumulative-layout-shift") * 10000) / 10000.0,
         millis(audits, "speed-index"),
         lcpElement(audits),
         runtimeError,
         warningTexts.isEmpty() ? null : truncate(String.join(" | ", warningTexts), 2000),
         findings(categories, audits),
         fieldData(root)));
   }



   /*******************************************************************************
    ** The findings of the categories that ran.
    *******************************************************************************/
   static List<Finding> findings(JSONObject categories, JSONObject audits)
   {
      Map<String, Finding> findings = new LinkedHashMap<>();
      for(Map.Entry<String, String> category : CATEGORIES.entrySet())
      {
         JSONObject object = categories.optJSONObject(category.getKey());
         JSONArray  refs   = object == null ? null : object.optJSONArray("auditRefs");
         for(int i = 0; refs != null && i < refs.length(); i++)
         {
            JSONObject ref = refs.getJSONObject(i);
            String     id  = ref.optString("id");
            if("metrics".equals(ref.optString("group")) || findings.containsKey(id))
            {
               continue;
            }
            JSONObject audit = audits.optJSONObject(id);
            if(audit == null || !audit.has("score") || audit.isNull("score") || UNSCORED.contains(audit.optString("scoreDisplayMode")))
            {
               continue;
            }
            double score = audit.getDouble("score");
            if(score >= PASSING)
            {
               continue;
            }

            JSONObject details = audit.optJSONObject("details");
            String     kind;
            if(id.endsWith("-insight"))
            {
               kind = "INSIGHT";
            }
            else if(details != null && "opportunity".equals(details.optString("type")))
            {
               kind = "OPPORTUNITY";
            }
            else if(!"PERFORMANCE".equals(category.getValue()))
            {
               kind = "FAILED_AUDIT";
            }
            else
            {
               kind = "DIAGNOSTIC";
            }

            Integer savingsMs    = details != null && details.has("overallSavingsMs") ? (int) Math.round(details.getDouble("overallSavingsMs")) : null;
            Long    savingsBytes = details != null && details.has("overallSavingsBytes") ? Math.round(details.getDouble("overallSavingsBytes")) : null;
            JSONObject metricSavings = audit.optJSONObject("metricSavings");

            findings.put(id, new Finding(category.getValue(), id, kind, truncate(audit.optString("title", id), MAX_TEXT), score,
               truncate(audit.optString("displayValue", null), 200), savingsMs, savingsBytes,
               metricSavings == null ? null : metricSavings.toString(), topItems(details)));
         }
      }
      return (new ArrayList<>(findings.values()));
   }



   /*******************************************************************************
    ** Up to five URLs or element selectors from an audit's details, as a JSON
    ** array, or null.
    *******************************************************************************/
   static String topItems(JSONObject details)
   {
      if(details == null)
      {
         return (null);
      }
      List<String> items = new ArrayList<>();
      collectItems(details, items, 0);
      if(items.isEmpty())
      {
         return (null);
      }
      return (new JSONArray(items).toString());
   }



   /*******************************************************************************
    ** Walk "items" arrays (insights nest them) collecting url or node selector.
    *******************************************************************************/
   private static void collectItems(JSONObject details, List<String> items, int depth)
   {
      JSONArray array = details.optJSONArray("items");
      for(int i = 0; array != null && i < array.length() && items.size() < MAX_ITEMS && depth < 3; i++)
      {
         Object value = array.get(i);
         if(!(value instanceof JSONObject item))
         {
            continue;
         }
         String found = null;
         if(item.opt("url") instanceof String url && !url.isBlank())
         {
            found = url;
         }
         else if(item.optJSONObject("node") != null && item.getJSONObject("node").opt("selector") instanceof String selector)
         {
            found = selector;
         }
         if(found != null)
         {
            String text = truncate(found, MAX_TEXT);
            if(!items.contains(text))
            {
               items.add(text);
            }
         }
         else if(item.has("items"))
         {
            collectItems(item, items, depth + 1);
         }
      }
   }



   /*******************************************************************************
    ** The LCP element's selector, from the LCP breakdown insight (Lighthouse
    ** 13) or the older largest-contentful-paint-element audit.
    *******************************************************************************/
   static String lcpElement(JSONObject audits)
   {
      for(String id : List.of("lcp-breakdown-insight", "lcp-phases-insight", "largest-contentful-paint-element"))
      {
         JSONObject audit = audits.optJSONObject(id);
         String     found = audit == null ? null : firstSelector(audit.optJSONObject("details"), 0);
         if(found != null)
         {
            return (truncate(found, MAX_TEXT));
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** The first node selector anywhere under a JSON value.
    *******************************************************************************/
   private static String firstSelector(Object value, int depth)
   {
      if(depth > 6 || value == null)
      {
         return (null);
      }
      if(value instanceof JSONObject object)
      {
         if("node".equals(object.optString("type")) && object.opt("selector") instanceof String selector)
         {
            return (selector);
         }
         for(String key : object.keySet())
         {
            String found = firstSelector(object.get(key), depth + 1);
            if(found != null)
            {
               return (found);
            }
         }
      }
      else if(value instanceof JSONArray array)
      {
         for(int i = 0; i < array.length(); i++)
         {
            String found = firstSelector(array.get(i), depth + 1);
            if(found != null)
            {
               return (found);
            }
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** Google field data PSI attached for the URL and the origin.
    *******************************************************************************/
   static List<FieldData> fieldData(JSONObject root)
   {
      List<FieldData> result = new ArrayList<>();
      for(String member : List.of("loadingExperience", "originLoadingExperience"))
      {
         JSONObject experience = root.optJSONObject(member);
         JSONObject metrics    = experience == null ? null : experience.optJSONObject("metrics");
         if(metrics == null || metrics.isEmpty())
         {
            continue;
         }
         boolean fallback = experience.optBoolean("origin_fallback", false);
         String  scope    = "originLoadingExperience".equals(member) || fallback ? "ORIGIN" : "URL";
         Integer cls      = percentile(metrics, "CUMULATIVE_LAYOUT_SHIFT_SCORE");
         result.add(new FieldData(scope, experience.optString("id", null), fallback,
            percentile(metrics, "LARGEST_CONTENTFUL_PAINT_MS"),
            percentile(metrics, "INTERACTION_TO_NEXT_PAINT"),
            cls == null ? null : cls / 100.0,
            percentile(metrics, "FIRST_CONTENTFUL_PAINT_MS"),
            percentile(metrics, "EXPERIMENTAL_TIME_TO_FIRST_BYTE"),
            metrics.toString()));
      }
      return (result);
   }



   /*******************************************************************************
    ** A PSI field metric's percentile, or null.
    *******************************************************************************/
   private static Integer percentile(JSONObject metrics, String key)
   {
      JSONObject metric = metrics.optJSONObject(key);
      return (metric != null && metric.has("percentile") ? metric.getInt("percentile") : null);
   }



   /*******************************************************************************
    ** An audit's numericValue, or null.
    *******************************************************************************/
   private static Double numeric(JSONObject audits, String id)
   {
      JSONObject audit = audits.optJSONObject(id);
      return (audit != null && audit.has("numericValue") && !audit.isNull("numericValue") ? audit.getDouble("numericValue") : null);
   }



   /*******************************************************************************
    ** An audit's numericValue in whole milliseconds, or null.
    *******************************************************************************/
   private static Integer millis(JSONObject audits, String id)
   {
      Double value = numeric(audits, id);
      return (value == null ? null : (int) Math.round(value));
   }



   /*******************************************************************************
    ** Cut text to a length.
    *******************************************************************************/
   static String truncate(String text, int max)
   {
      return (text == null || text.length() <= max ? text : text.substring(0, max));
   }
}
