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


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpServer;


/*******************************************************************************
 ** A local HTTP server standing in for the Google APIs in tests: it answers
 ** with queued responses (the last one repeats), and records every request's
 ** query string and body.
 *******************************************************************************/
public class FixtureServer implements AutoCloseable
{
   /*******************************************************************************
    ** One response.
    *******************************************************************************/
   public record Response(int status, String body)
   {
   }



   /*******************************************************************************
    ** One recorded request.
    *******************************************************************************/
   public record Request(String method, String query, String body)
   {
   }



   private final HttpServer       server;
   private final Deque<Response>  responses = new ArrayDeque<>();
   private final List<Request>    requests  = new CopyOnWriteArrayList<>();



   /*******************************************************************************
    ** Start a server.
    *******************************************************************************/
   public FixtureServer() throws IOException
   {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange ->
      {
         String body;
         try(InputStream in = exchange.getRequestBody())
         {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
         }
         requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(), body));
         Response response;
         synchronized(responses)
         {
            response = responses.size() > 1 ? responses.pollFirst() : responses.peekFirst();
         }
         if(response == null)
         {
            response = new Response(500, "{\"error\":{\"message\":\"no response configured\"}}");
         }
         byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
         exchange.getResponseHeaders().add("Content-Type", "application/json");
         exchange.sendResponseHeaders(response.status(), bytes.length);
         try(OutputStream out = exchange.getResponseBody())
         {
            out.write(bytes);
         }
         exchange.close();
      });
      server.start();
   }



   /*******************************************************************************
    ** Queue responses (the last one repeats).
    *******************************************************************************/
   public FixtureServer respond(Response... queued)
   {
      synchronized(responses)
      {
         responses.clear();
         responses.addAll(List.of(queued));
      }
      return (this);
   }



   /*******************************************************************************
    ** A fixture file's content.
    *******************************************************************************/
   public static String fixture(String name) throws IOException
   {
      try(InputStream in = FixtureServer.class.getClassLoader().getResourceAsStream("fixtures/" + name))
      {
         return (new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
   }



   /*******************************************************************************
    ** The server's base URL.
    *******************************************************************************/
   public String url(String path)
   {
      return ("http://127.0.0.1:" + server.getAddress().getPort() + path);
   }



   /*******************************************************************************
    ** Requests so far.
    *******************************************************************************/
   public List<Request> getRequests()
   {
      return (requests);
   }



   /*******************************************************************************
    ** Stop.
    *******************************************************************************/
   @Override
   public void close()
   {
      server.stop(0);
   }
}
