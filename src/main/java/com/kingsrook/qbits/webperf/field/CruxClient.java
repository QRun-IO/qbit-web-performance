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

package com.kingsrook.qbits.webperf.field;


import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Google field data from the Chrome UX Report API (design §2.1, §2.3):
 ** p75 over a rolling 28 days for a URL or an origin, per form factor.
 **
 ** A page or site Google has no data for (not public, or not popular enough,
 ** the usual case for a small site) is answered 404 by the API and reported
 ** here as NO_DATA: a real result, stored and shown as such, never hidden or
 ** replaced. The API key is never part of an exception or stored value.
 *******************************************************************************/
public class CruxClient implements Serializable
{
   private static final long serialVersionUID = 1L;

   public static final String DEFAULT_ENDPOINT = "https://chromeuxreport.googleapis.com/v1/records:queryRecord";

   /** Form factors queried; ALL omits the form factor (every device combined). */
   public static final List<String> FORM_FACTORS = List.of("ALL", "PHONE", "DESKTOP");

   private static final Set<String> SCOPES = Set.of("URL", "ORIGIN");

   private final String endpoint;
   private final String apiKey;



   /*******************************************************************************
    ** The result of one query.
    **
    ** @param scope         URL or ORIGIN
    ** @param key           the URL or origin
    ** @param formFactor    ALL, PHONE, DESKTOP or TABLET
    ** @param status        OK or NO_DATA
    ** @param periodStart   first day of the collection period (null for NO_DATA)
    ** @param periodEnd     last day of the collection period (null for NO_DATA)
    ** @param p75LcpMs      LCP p75
    ** @param p75InpMs      INP p75
    ** @param p75Cls        CLS p75
    ** @param p75FcpMs      FCP p75
    ** @param p75TtfbMs     TTFB p75
    ** @param distributions per-metric good / needs-improvement / poor densities as JSON
    *******************************************************************************/
   public record CruxResult(String scope, String key, String formFactor, String status, LocalDate periodStart, LocalDate periodEnd, Integer p75LcpMs,
                            Integer p75InpMs, Double p75Cls, Integer p75FcpMs, Integer p75TtfbMs, String distributions) implements Serializable
   {
   }



   /*******************************************************************************
    ** A client on Google's endpoint.
    **
    ** @param apiKey the Google API key
    *******************************************************************************/
   public CruxClient(String apiKey)
   {
      this(DEFAULT_ENDPOINT, apiKey);
   }



   /*******************************************************************************
    ** A client on an endpoint (tests point it at a local server).
    **
    ** @param endpoint the queryRecord URL
    ** @param apiKey   the Google API key
    *******************************************************************************/
   public CruxClient(String endpoint, String apiKey)
   {
      if(apiKey == null || apiKey.isBlank())
      {
         throw (new IllegalArgumentException("A CrUX client needs an API key"));
      }
      this.endpoint = endpoint;
      this.apiKey = apiKey;
   }



   /*******************************************************************************
    ** Query one URL or origin for one form factor.
    **
    ** @param scope      URL or ORIGIN
    ** @param key        the URL or origin
    ** @param formFactor ALL, PHONE, DESKTOP or TABLET
    ** @return the result (NO_DATA when Google has none)
    ** @throws IOException on a transport failure or an unexpected response
    ** @throws InterruptedException when interrupted
    *******************************************************************************/
   public CruxResult query(String scope, String key, String formFactor) throws IOException, InterruptedException
   {
      if(!SCOPES.contains(scope))
      {
         throw (new IllegalArgumentException("Unknown scope: " + scope));
      }
      JSONObject body = new JSONObject().put("ORIGIN".equals(scope) ? "origin" : "url", key);
      if(!"ALL".equals(formFactor))
      {
         body.put("formFactor", formFactor);
      }

      HttpClient  client  = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)))
         .timeout(Duration.ofSeconds(30))
         .header("Content-Type", "application/json")
         .header("Accept", "application/json")
         .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();

      HttpResponse<String> response;
      try
      {
         response = client.send(request, HttpResponse.BodyHandlers.ofString());
      }
      catch(IOException e)
      {
         throw (new IOException("CrUX call failed for " + key + " (" + e.getClass().getSimpleName() + ")"));
      }

      if(response.statusCode() == 404)
      {
         return (new CruxResult(scope, key, formFactor, "NO_DATA", null, null, null, null, null, null, null, null));
      }
      if(response.statusCode() != 200)
      {
         throw (new IOException("CrUX returned HTTP " + response.statusCode() + " for " + key));
      }
      return (parse(scope, key, formFactor, response.body()));
   }



   /*******************************************************************************
    ** Parse a queryRecord response.
    **
    ** @param scope      URL or ORIGIN
    ** @param key        the URL or origin
    ** @param formFactor the form factor
    ** @param json       the response body
    ** @return the result
    *******************************************************************************/
   static CruxResult parse(String scope, String key, String formFactor, String json)
   {
      JSONObject record  = new JSONObject(json).optJSONObject("record");
      JSONObject metrics = record == null ? null : record.optJSONObject("metrics");
      if(metrics == null || metrics.isEmpty())
      {
         return (new CruxResult(scope, key, formFactor, "NO_DATA", null, null, null, null, null, null, null, null));
      }

      JSONObject distributions = new JSONObject();
      for(String name : metrics.keySet())
      {
         JSONArray histogram = metrics.getJSONObject(name).optJSONArray("histogram");
         if(histogram != null)
         {
            JSONArray densities = new JSONArray();
            for(int i = 0; i < histogram.length(); i++)
            {
               densities.put(histogram.getJSONObject(i).optDouble("density", 0));
            }
            distributions.put(name, densities);
         }
      }

      JSONObject period = record.optJSONObject("collectionPeriod");
      Double     cls    = p75(metrics, "cumulative_layout_shift");
      return (new CruxResult(scope, key, formFactor, "OK",
         period == null ? null : date(period.optJSONObject("firstDate")),
         period == null ? null : date(period.optJSONObject("lastDate")),
         round(p75(metrics, "largest_contentful_paint")),
         round(p75(metrics, "interaction_to_next_paint")),
         cls == null ? null : Math.round(cls * 10000) / 10000.0,
         round(p75(metrics, "first_contentful_paint")),
         round(p75(metrics, "experimental_time_to_first_byte")),
         distributions.toString()));
   }



   /*******************************************************************************
    ** A metric's p75 (CrUX sends CLS as a string), or null.
    *******************************************************************************/
   private static Double p75(JSONObject metrics, String name)
   {
      JSONObject metric      = metrics.optJSONObject(name);
      JSONObject percentiles = metric == null ? null : metric.optJSONObject("percentiles");
      if(percentiles == null || !percentiles.has("p75") || percentiles.isNull("p75"))
      {
         return (null);
      }
      Object value = percentiles.get("p75");
      try
      {
         return (value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value)));
      }
      catch(NumberFormatException e)
      {
         return (null);
      }
   }



   /*******************************************************************************
    ** A whole number, or null.
    *******************************************************************************/
   private static Integer round(Double value)
   {
      return (value == null ? null : (int) Math.round(value));
   }



   /*******************************************************************************
    ** A CrUX {year, month, day} date, or null.
    *******************************************************************************/
   private static LocalDate date(JSONObject date)
   {
      if(date == null || !date.has("year"))
      {
         return (null);
      }
      return (LocalDate.of(date.getInt("year"), date.getInt("month"), date.getInt("day")));
   }



   /*******************************************************************************
    ** No key in the string form.
    *******************************************************************************/
   @Override
   public String toString()
   {
      return ("CruxClient{endpoint=" + endpoint + ", apiKey=<redacted>}");
   }
}
