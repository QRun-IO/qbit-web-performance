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


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import com.kingsrook.qbits.webperf.spi.UrlSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;


/*******************************************************************************
 ** The URLs of a site's sitemap.xml (the sitemaps.org protocol), following a
 ** sitemap index one level deep. Only http(s) URLs on the sitemap's own host
 ** are kept (a sitemap may not list other sites), and paths under the ignored
 ** prefixes (admin, preview, draft and API paths by default) are skipped.
 ** URLs keep the sitemap's order; duplicates are dropped.
 **
 ** The XML parser refuses DTDs and external entities, so a hostile sitemap
 ** cannot read local files or make the backend fetch other URLs.
 *******************************************************************************/
public class SitemapUrlSource implements UrlSource
{
   private static final long serialVersionUID = 1L;

   public static final List<String> DEFAULT_IGNORED_PREFIXES = List.of("/admin", "/preview", "/draft", "/api");
   public static final int          MAX_URLS                 = 50_000;
   public static final int          MAX_BYTES                = 10 * 1024 * 1024;
   public static final Duration     TIMEOUT                  = Duration.ofSeconds(20);

   private final String       sitemapUrl;
   private final List<String> ignoredPrefixes;



   /*******************************************************************************
    ** A source over a sitemap with the default ignored prefixes.
    **
    ** @param sitemapUrl the absolute sitemap URL
    *******************************************************************************/
   public SitemapUrlSource(String sitemapUrl)
   {
      this(sitemapUrl, DEFAULT_IGNORED_PREFIXES);
   }



   /*******************************************************************************
    ** A source over a sitemap.
    **
    ** @param sitemapUrl      the absolute sitemap URL
    ** @param ignoredPrefixes path prefixes never tested
    *******************************************************************************/
   public SitemapUrlSource(String sitemapUrl, List<String> ignoredPrefixes)
   {
      URI uri = URI.create(sitemapUrl);
      if(uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
      {
         throw (new IllegalArgumentException("The sitemap URL must be an absolute http(s) URL: " + sitemapUrl));
      }
      this.sitemapUrl = sitemapUrl;
      this.ignoredPrefixes = List.copyOf(ignoredPrefixes);
   }



   /*******************************************************************************
    ** Fetch and parse the sitemap (and a sitemap index's sitemaps).
    *******************************************************************************/
   @Override
   public List<String> urls() throws Exception
   {
      HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
      String     host   = URI.create(sitemapUrl).getHost().toLowerCase(Locale.ROOT);

      Set<String> urls   = new LinkedHashSet<>();
      Document    root   = parse(fetch(client, sitemapUrl));
      String      kind   = root.getDocumentElement().getLocalName();
      if("sitemapindex".equals(kind))
      {
         for(String child : locations(root, "sitemap"))
         {
            if(sameHost(child, host))
            {
               addPages(parse(fetch(client, child)), host, urls);
            }
         }
      }
      else if("urlset".equals(kind))
      {
         addPages(root, host, urls);
      }
      else
      {
         throw (new IOException("Not a sitemap: the root element is <" + kind + ">"));
      }
      return (new ArrayList<>(urls));
   }



   /*******************************************************************************
    ** Add a urlset's page URLs.
    *******************************************************************************/
   private void addPages(Document document, String host, Set<String> urls) throws IOException
   {
      for(String location : locations(document, "url"))
      {
         if(urls.size() >= MAX_URLS)
         {
            throw (new IOException("The sitemap lists more than " + MAX_URLS + " URLs"));
         }
         if(sameHost(location, host) && !isIgnored(URI.create(location).getPath()))
         {
            urls.add(location);
         }
      }
   }



   /*******************************************************************************
    ** Whether a path is under an ignored prefix.
    **
    ** @param path the URL path
    ** @return true when it is never tested
    *******************************************************************************/
   boolean isIgnored(String path)
   {
      String value = path == null || path.isEmpty() ? "/" : path;
      for(String prefix : ignoredPrefixes)
      {
         if(value.equals(prefix) || value.startsWith(prefix + "/"))
         {
            return (true);
         }
      }
      return (false);
   }



   /*******************************************************************************
    ** Whether a URL is an http(s) URL on the host.
    *******************************************************************************/
   static boolean sameHost(String url, String host)
   {
      try
      {
         URI uri = URI.create(url);
         return (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null
            && host.equals(uri.getHost().toLowerCase(Locale.ROOT)));
      }
      catch(IllegalArgumentException e)
      {
         return (false);
      }
   }



   /*******************************************************************************
    ** The trimmed <loc> values of the given entry elements (url or sitemap).
    *******************************************************************************/
   static List<String> locations(Document document, String entry)
   {
      List<String> result  = new ArrayList<>();
      NodeList     entries = document.getElementsByTagNameNS("*", entry);
      for(int i = 0; i < entries.getLength(); i++)
      {
         NodeList locs = ((Element) entries.item(i)).getElementsByTagNameNS("*", "loc");
         if(locs.getLength() > 0)
         {
            String location = locs.item(0).getTextContent().trim();
            if(!location.isEmpty())
            {
               result.add(location);
            }
         }
      }
      return (result);
   }



   /*******************************************************************************
    ** GET a URL; anything but 200 is a failure.
    *******************************************************************************/
   private static byte[] fetch(HttpClient client, String url) throws IOException, InterruptedException
   {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
         .header("Accept", "application/xml, text/xml")
         .header("User-Agent", "qbit-web-performance (sitemap sync)")
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
      return (response.body());
   }



   /*******************************************************************************
    ** Parse XML namespace-aware, with DTDs and external entities refused.
    *******************************************************************************/
   static Document parse(byte[] xml) throws Exception
   {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return (builder.parse(new ByteArrayInputStream(xml)));
   }



   /*******************************************************************************
    ** Getter for sitemapUrl.
    **
    ** @return the sitemap URL
    *******************************************************************************/
   public String getSitemapUrl()
   {
      return (sitemapUrl);
   }
}
