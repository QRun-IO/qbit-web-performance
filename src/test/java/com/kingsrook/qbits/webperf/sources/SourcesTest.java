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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


/*******************************************************************************
 ** The sitemap source and the build-id probe over real HTTP (a local JDK
 ** HttpServer serving fixed bodies): parsing, filtering, sitemap indexes,
 ** refusal of DTDs, and the build endpoint contract of Website-Frontend's
 ** /api/build.
 *******************************************************************************/
class SourcesTest
{
   private HttpServer          server;
   private String              base;
   private Map<String, String> bodies;
   private Map<String, Integer> statuses;



   /*******************************************************************************
    ** Start a server that answers each path with its configured body.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws IOException
   {
      bodies = new HashMap<>();
      statuses = new HashMap<>();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange ->
      {
         String path   = exchange.getRequestURI().getPath();
         String body   = bodies.getOrDefault(path, "");
         int    status = statuses.getOrDefault(path, bodies.containsKey(path) ? 200 : 404);
         byte[] bytes  = body.getBytes(StandardCharsets.UTF_8);
         exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
         if(bytes.length > 0)
         {
            try(OutputStream out = exchange.getResponseBody())
            {
               out.write(bytes);
            }
         }
         exchange.close();
      });
      server.start();
      base = "http://127.0.0.1:" + server.getAddress().getPort();
   }



   /*******************************************************************************
    ** Stop the server.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      server.stop(0);
   }



   /*******************************************************************************
    ** A urlset is read in order; other hosts, ignored paths and duplicates are
    ** dropped.
    *******************************************************************************/
   @Test
   void testSitemapUrlset() throws Exception
   {
      bodies.put("/sitemap.xml", """
         <?xml version="1.0" encoding="UTF-8"?>
         <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
           <url><loc>%1$s/</loc><lastmod>2026-10-01</lastmod></url>
           <url><loc> %1$s/blogs/tech/hello </loc></url>
           <url><loc>%1$s/blogs/tech/hello</loc></url>
           <url><loc>https://elsewhere.example/page</loc></url>
           <url><loc>%1$s/admin/settings</loc></url>
           <url><loc>%1$s/preview</loc></url>
           <url><loc>%1$s/projects/qqq</loc></url>
           <url><loc>ftp://127.0.0.1/file</loc></url>
         </urlset>""".formatted(base));

      assertThat(new SitemapUrlSource(base + "/sitemap.xml").urls())
         .containsExactly(base + "/", base + "/blogs/tech/hello", base + "/projects/qqq");
   }



   /*******************************************************************************
    ** A sitemap index is followed one level (same host only).
    *******************************************************************************/
   @Test
   void testSitemapIndex() throws Exception
   {
      bodies.put("/sitemap.xml", """
         <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
           <sitemap><loc>%1$s/pages.xml</loc></sitemap>
           <sitemap><loc>%1$s/posts.xml</loc></sitemap>
           <sitemap><loc>https://elsewhere.example/sitemap.xml</loc></sitemap>
         </sitemapindex>""".formatted(base));
      bodies.put("/pages.xml", "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><url><loc>%s/about</loc></url></urlset>".formatted(base));
      bodies.put("/posts.xml", "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><url><loc>%s/blogs/a</loc></url></urlset>".formatted(base));

      assertThat(new SitemapUrlSource(base + "/sitemap.xml").urls()).containsExactly(base + "/about", base + "/blogs/a");
   }



   /*******************************************************************************
    ** Failures are reported, never turned into an empty list: HTTP errors,
    ** documents that are not sitemaps, and DTDs (entity expansion) refused.
    *******************************************************************************/
   @Test
   void testSitemapFailures()
   {
      statuses.put("/sitemap.xml", 503);
      bodies.put("/sitemap.xml", "down");
      assertThatThrownBy(() -> new SitemapUrlSource(base + "/sitemap.xml").urls()).hasMessageContaining("HTTP 503");

      statuses.remove("/sitemap.xml");
      bodies.put("/sitemap.xml", "<html><body>not a sitemap</body></html>");
      assertThatThrownBy(() -> new SitemapUrlSource(base + "/sitemap.xml").urls()).hasMessageContaining("Not a sitemap");

      bodies.put("/sitemap.xml", """
         <?xml version="1.0"?>
         <!DOCTYPE urlset [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
         <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>&xxe;</loc></url></urlset>""");
      assertThatThrownBy(() -> new SitemapUrlSource(base + "/sitemap.xml").urls()).hasMessageContaining("DOCTYPE");

      assertThatThrownBy(() -> new SitemapUrlSource("/sitemap.xml")).isInstanceOf(IllegalArgumentException.class);
   }



   /*******************************************************************************
    ** Ignored prefixes match whole path segments.
    *******************************************************************************/
   @Test
   void testIgnoredPrefixes()
   {
      SitemapUrlSource source = new SitemapUrlSource(base + "/sitemap.xml");
      assertThat(source.isIgnored("/admin")).isTrue();
      assertThat(source.isIgnored("/api/e")).isTrue();
      assertThat(source.isIgnored("/administration")).isFalse();
      assertThat(source.isIgnored("")).isFalse();
      assertEquals(base + "/sitemap.xml", source.getSitemapUrl());
   }



   /*******************************************************************************
    ** The build probe reads /api/build's deploymentId; anything implausible is
    ** reported as no build, and transport errors are failures.
    *******************************************************************************/
   @Test
   void testBuildIdProbe() throws Exception
   {
      HttpBuildIdProbe probe = new HttpBuildIdProbe("FRONTEND", base + "/api/build");
      assertEquals("FRONTEND", probe.component());
      assertEquals(base + "/api/build", probe.getUrl());

      bodies.put("/api/build", "{\"deploymentId\":\"3F2A9C1D0E\"}");
      assertEquals("3f2a9c1d0e", probe.currentBuildId());

      bodies.put("/api/build", "{\"deploymentId\":null}");
      assertNull(probe.currentBuildId());

      bodies.put("/api/build", "{\"deploymentId\":\"main; rm -rf /\"}");
      assertNull(probe.currentBuildId());

      bodies.put("/api/build", "{\"version\":\"abcdef1\"}");
      assertEquals("abcdef1", new HttpBuildIdProbe("BACKEND", base + "/api/build", "version").currentBuildId());

      bodies.put("/api/build", "<html>");
      assertThatThrownBy(probe::currentBuildId).hasMessageContaining("JSON");

      statuses.put("/api/build", 500);
      assertThatThrownBy(probe::currentBuildId).hasMessageContaining("HTTP 500");

      assertThatThrownBy(() -> new HttpBuildIdProbe("", base)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new HttpBuildIdProbe("X", "frontend/api/build")).isInstanceOf(IllegalArgumentException.class);
   }
}
