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

package com.kingsrook.qbits.webperf.sources;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import com.kingsrook.qbits.webperf.spi.DeployProbe;
import org.json.JSONException;
import org.json.JSONObject;


/*******************************************************************************
 ** Reads a component's build id from a JSON endpoint, by default the
 ** "deploymentId" member of a Next.js site's /api/build route
 ** ({"deploymentId": "&lt;commit sha&gt;"}, Website-Frontend). Only a plausible
 ** git object id is reported; a missing or malformed value is null (the
 ** component reports no build), never a guess.
 *******************************************************************************/
public class HttpBuildIdProbe implements DeployProbe
{
   private static final long serialVersionUID = 1L;

   public static final String   DEFAULT_MEMBER = "deploymentId";
   public static final Duration TIMEOUT        = Duration.ofSeconds(10);
   public static final int      MAX_BYTES      = 4096;

   private static final Pattern BUILD_ID = Pattern.compile("^[0-9a-f]{7,64}$");

   private final String component;
   private final String url;
   private final String member;



   /*******************************************************************************
    ** A probe of a JSON endpoint's "deploymentId".
    **
    ** @param component the component name, e.g. FRONTEND
    ** @param url       the absolute endpoint URL (in-cluster is fine)
    *******************************************************************************/
   public HttpBuildIdProbe(String component, String url)
   {
      this(component, url, DEFAULT_MEMBER);
   }



   /*******************************************************************************
    ** A probe of a JSON endpoint member.
    **
    ** @param component the component name
    ** @param url       the absolute endpoint URL
    ** @param member    the JSON member holding the build id
    *******************************************************************************/
   public HttpBuildIdProbe(String component, String url, String member)
   {
      URI uri = URI.create(url);
      if(uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
      {
         throw (new IllegalArgumentException("The build endpoint must be an absolute http(s) URL: " + url));
      }
      if(component == null || component.isBlank())
      {
         throw (new IllegalArgumentException("A deploy probe needs a component name"));
      }
      this.component = component;
      this.url = url;
      this.member = member;
   }



   /*******************************************************************************
    ** The component name.
    *******************************************************************************/
   @Override
   public String component()
   {
      return (component);
   }



   /*******************************************************************************
    ** GET the endpoint and read the build id.
    *******************************************************************************/
   @Override
   public String currentBuildId() throws Exception
   {
      HttpClient  client  = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
         .header("Accept", "application/json")
         .header("Cache-Control", "no-cache")
         .header("User-Agent", "qbit-web-performance (deploy probe)")
         .GET().build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if(response.statusCode() != 200)
      {
         throw (new IOException("GET " + url + " returned HTTP " + response.statusCode()));
      }
      if(response.body().length > MAX_BYTES)
      {
         throw (new IOException("GET " + url + " returned more than " + MAX_BYTES + " bytes"));
      }
      return (buildIdFrom(new String(response.body(), StandardCharsets.UTF_8), member));
   }



   /*******************************************************************************
    ** The build id in a JSON body, or null when absent or implausible.
    **
    ** @param json   the body
    ** @param member the member name
    ** @return the lower-case build id or null
    ** @throws IOException when the body is not a JSON object
    *******************************************************************************/
   static String buildIdFrom(String json, String member) throws IOException
   {
      JSONObject object;
      try
      {
         object = new JSONObject(json);
      }
      catch(JSONException e)
      {
         throw (new IOException("The build endpoint did not return a JSON object", e));
      }
      if(!(object.opt(member) instanceof String value))
      {
         return (null);
      }
      String id = value.trim().toLowerCase(Locale.ROOT);
      return (BUILD_ID.matcher(id).matches() ? id : null);
   }



   /*******************************************************************************
    ** Getter for url.
    **
    ** @return the endpoint URL
    *******************************************************************************/
   public String getUrl()
   {
      return (url);
   }
}
