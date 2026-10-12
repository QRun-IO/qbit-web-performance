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


import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import com.kingsrook.qbits.webperf.spi.LabRunner;


/*******************************************************************************
 ** Runs a lab test through the PageSpeed Insights API v5 (design §2.1, D2):
 ** Lighthouse run by Google, from outside the site's network, so it measures
 ** what a visitor gets (and later the CDN). One call per URL and strategy,
 ** with every requested category (D13).
 **
 ** The API key is sent as the key parameter and never appears in an
 ** exception, log line or stored value: messages name the URL and status
 ** only.
 *******************************************************************************/
public class PsiLabRunner implements LabRunner
{
   private static final long serialVersionUID = 1L;

   public static final String   DEFAULT_ENDPOINT = "https://www.googleapis.com/pagespeedonline/v5/runPagespeed";
   public static final String   NAME             = "PSI";
   public static final Duration TIMEOUT          = Duration.ofSeconds(150);
   public static final int      MAX_BYTES        = 20 * 1024 * 1024;

   private static final Set<String> STRATEGIES = Set.of("MOBILE", "DESKTOP");
   private static final Set<String> CATEGORIES = Set.of("PERFORMANCE", "ACCESSIBILITY", "BEST_PRACTICES", "SEO");

   private final String endpoint;
   private final String apiKey;



   /*******************************************************************************
    ** A runner on Google's endpoint.
    **
    ** @param apiKey the Google API key
    *******************************************************************************/
   public PsiLabRunner(String apiKey)
   {
      this(DEFAULT_ENDPOINT, apiKey);
   }



   /*******************************************************************************
    ** A runner on an endpoint (tests point it at a local server).
    **
    ** @param endpoint the runPagespeed URL
    ** @param apiKey   the Google API key
    *******************************************************************************/
   public PsiLabRunner(String endpoint, String apiKey)
   {
      if(apiKey == null || apiKey.isBlank())
      {
         throw (new IllegalArgumentException("A PageSpeed Insights runner needs an API key"));
      }
      this.endpoint = endpoint;
      this.apiKey = apiKey;
   }



   /*******************************************************************************
    ** The runner name stored on runs.
    *******************************************************************************/
   @Override
   public String name()
   {
      return (NAME);
   }



   /*******************************************************************************
    ** Call the API. Any HTTP status is returned (the worker decides about
    ** retries); only transport failures throw.
    *******************************************************************************/
   @Override
   public LabResponse run(LabRequest request) throws Exception
   {
      HttpClient  client      = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
      HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(requestUrl(request)))
         .timeout(TIMEOUT)
         .header("Accept", "application/json")
         .GET().build();
      HttpResponse<byte[]> response;
      try
      {
         response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
      }
      catch(IOException e)
      {
         //////////////////////////////////////////////////////////////////
         // the exception text can carry the request URL (with the key): //
         // report the target only                                       //
         //////////////////////////////////////////////////////////////////
         throw (new IOException("PageSpeed Insights call failed for " + request.url() + " (" + e.getClass().getSimpleName() + ")"));
      }
      if(response.body().length > MAX_BYTES)
      {
         throw (new IOException("PageSpeed Insights returned more than " + MAX_BYTES + " bytes for " + request.url()));
      }
      return (new LabResponse(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), Instant.now()));
   }



   /*******************************************************************************
    ** The request URL: url, strategy, every category, locale and key.
    **
    ** @param request the test
    ** @return the URL
    *******************************************************************************/
   String requestUrl(LabRequest request)
   {
      String strategy = request.strategy() == null ? "" : request.strategy().toUpperCase(Locale.ROOT);
      if(!STRATEGIES.contains(strategy))
      {
         throw (new IllegalArgumentException("Unknown strategy: " + request.strategy()));
      }
      StringBuilder url = new StringBuilder(endpoint)
         .append("?url=").append(encode(request.url()))
         .append("&strategy=").append(strategy)
         .append("&locale=en");
      for(String category : request.categories())
      {
         if(!CATEGORIES.contains(category))
         {
            throw (new IllegalArgumentException("Unknown category: " + category));
         }
         url.append("&category=").append(category);
      }
      url.append("&key=").append(encode(apiKey));
      return (url.toString());
   }



   /*******************************************************************************
    ** URL-encode a value.
    *******************************************************************************/
   private static String encode(String value)
   {
      return (URLEncoder.encode(value, StandardCharsets.UTF_8));
   }



   /*******************************************************************************
    ** No key in the string form.
    *******************************************************************************/
   @Override
   public String toString()
   {
      return ("PsiLabRunner{endpoint=" + endpoint + ", apiKey=<redacted>}");
   }
}
