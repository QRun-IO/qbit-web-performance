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

package com.kingsrook.qbits.webperf.service;


import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.kingsrook.qbits.webperf.WebPerfFeature;
import com.kingsrook.qbits.webperf.WebPerfQBitConfig;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qbits.webperf.spi.ChangeEventSource;
import com.kingsrook.qbits.webperf.spi.DeployProbe;
import com.kingsrook.qbits.webperf.store.WebPerfStore;
import org.json.JSONArray;


/*******************************************************************************
 ** Records source changes and attributes runs to the state observed at start.
 ** All identities and queue writes are durable; polling can safely repeat.
 *******************************************************************************/
public class WebPerfChangeService
{
   private final WebPerfStore store;
   private final WebPerfQBitConfig config;



   /*******************************************************************************
    ** Changes for one installation.
    *******************************************************************************/
   public WebPerfChangeService(WebPerfStore store)
   {
      this.store = store;
      this.config = store.getConfig();
   }



   /*******************************************************************************
    ** Poll configured sources. Failed probes propagate so the schedule retries.
    *******************************************************************************/
   public void poll(Instant now) throws Exception
   {
      if(config.isOn(WebPerfFeature.DEPLOY_PROBES))
      {
         for(DeployProbe probe : config.getDeployProbes())
         {
            String build = probe.currentBuildId();
            if(build != null && !build.isBlank())
            {
               String kind = "BACKEND".equalsIgnoreCase(probe.component()) ? "BACKEND_DEPLOY" : "FRONTEND_DEPLOY";
               Map<String, Object> previous = latest(kind, probe.component(), now);
               record(new ChangeEventSource.Change(kind, probe.component(), previous == null ? null : (String) previous.get("to_value"), build, now),
                  "PROBE", probe.component() + ":" + build, true);
            }
         }
      }
      for(int i = 0; i < config.getChangeEventSources().size(); i++)
      {
         String sourceKey = "source:" + i;
         List<Map<String, Object>> last = store.query("SELECT MAX(observed_at) AS observed_at FROM {change_event} WHERE site_key = ? AND note = ?",
            siteKey(), sourceKey);
         Instant since = (Instant) last.get(0).get("observed_at");
         for(ChangeEventSource.Change change : config.getChangeEventSources().get(i).changesSince(since == null ? Instant.EPOCH : since))
         {
            if(!"CDN_STAGE".equals(change.kind()) || config.isOn(WebPerfFeature.CDN_COMPARE))
            {
               record(change, "CDN_STAGE".equals(change.kind()) ? "QBIT_CDN" : "WEBHOOK", sourceKey, true);
            }
         }
      }
   }



   /*******************************************************************************
    ** Insert once per build or source event and queue affected pages atomically.
    ** The source SPI has no event id, so its complete tuple is its identity.
    *******************************************************************************/
   public long record(ChangeEventSource.Change change, String source, String sourceKey, boolean queue) throws SQLException
   {
      String identity = new JSONArray(List.of(siteKey(), source, sourceKey, change.kind(), change.component(), change.toValue(),
         "PROBE".equals(source) ? "" : change.observedAt().toString(), "PROBE".equals(source) ? "" : String.valueOf(change.fromValue()))).toString();
      String key = WebPerfService.sha256(identity.getBytes(StandardCharsets.UTF_8));
      try
      {
         return (store.transaction(tx ->
         {
            List<Map<String, Object>> existing = tx.query("SELECT id FROM {change_event} WHERE event_key = ?", key);
            if(!existing.isEmpty())
            {
               if("PROBE".equals(source))
               {
                  tx.update("UPDATE {change_event} SET last_seen_at = ? WHERE id = ? AND (last_seen_at IS NULL OR last_seen_at < ?)",
                     change.observedAt(), existing.get(0).get("id"), change.observedAt());
               }
               return (WebPerfStore.asLong(existing.get(0).get("id")));
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("kind", change.kind());
            values.put("component", change.component());
            values.put("from_value", change.fromValue());
            values.put("to_value", change.toValue());
            values.put("observed_at", change.observedAt());
            values.put("last_seen_at", change.observedAt());
            values.put("source", source);
            values.put("note", sourceKey);
            values.put("event_key", key);
            values.put("site_key", siteKey());
            long id = tx.insert(WebPerfSchema.CHANGE_EVENT, values);
            if(queue && config.isOn(WebPerfFeature.LAB))
            {
               queueChange(tx, id, change);
            }
            return (id);
         }));
      }
      catch(SQLException e)
      {
         /////////////////////////////////////////////////////////////////////
         // A competing poll may have committed this exact identity first. //
         /////////////////////////////////////////////////////////////////////
         if("23505".equals(e.getSQLState()))
         {
            List<Map<String, Object>> existing = store.query("SELECT id FROM {change_event} WHERE event_key = ?", key);
            if(!existing.isEmpty())
            {
               return (WebPerfStore.asLong(existing.get(0).get("id")));
            }
         }
         throw (e);
      }
   }



   /*******************************************************************************
    ** Page through targets; CDN sampling bypasses the ordinary queue merge.
    *******************************************************************************/
   private void queueChange(WebPerfStore tx, long eventId, ChangeEventSource.Change change) throws SQLException
   {
      WebPerfService service = new WebPerfService(new WebPerfRuntime(config), tx);
      boolean cdn = "CDN_STAGE".equals(change.kind());
      boolean deploy = change.kind().endsWith("DEPLOY");
      long after = 0;
      while(true)
      {
         List<Map<String, Object>> pages = tx.query("SELECT id, url FROM {target} WHERE site_key = ? AND enabled = TRUE AND id > ?"
            + (cdn || deploy ? " AND tier = 'KEY'" : "") + " ORDER BY id FETCH FIRST 100 ROWS ONLY", siteKey(), after);
         if(pages.isEmpty())
         {
            return;
         }
         for(Map<String, Object> page : pages)
         {
            after = WebPerfStore.asLong(page.get("id"));
            for(String strategy : config.getStrategies())
            {
               if(cdn && ("HEADERS_ONLY".equals(change.toValue()) || "LIVE".equals(change.toValue())))
               {
                  for(int sample = 0; sample < 3; sample++)
                  {
                     service.enqueueSample(page, strategy, "CHANGE_COMPARE", "change:" + eventId + ":1:" + sample,
                        change.observedAt().plusSeconds("LIVE".equals(change.toValue()) ? 3600 : 0));
                     if("LIVE".equals(change.toValue()))
                     {
                        service.enqueueSample(page, strategy, "CHANGE_COMPARE", "change:" + eventId + ":24:" + sample, change.observedAt().plusSeconds(86400));
                     }
                  }
               }
               else
               {
                  Instant due = change.observedAt().plusSeconds(deploy ? config.getDeploySettleSeconds() : config.getPublishSettleSeconds());
                  service.enqueue(List.of(new WebPerfService.RunRequest(after, (String) page.get("url"), strategy,
                     deploy ? "DEPLOY" : "PUBLISH", "change:" + eventId, null, due)), change.observedAt());
               }
            }
         }
      }
   }



   /*******************************************************************************
    ** Latest observed state at a time, optionally for a specific component.
    *******************************************************************************/
   public Map<String, Object> latest(String kind, String component, Instant at) throws SQLException
   {
      List<Map<String, Object>> rows = component == null
         ? store.query("SELECT * FROM {change_event} WHERE site_key = ? AND kind = ? AND observed_at <= ? ORDER BY last_seen_at DESC, id DESC FETCH FIRST 1 ROWS ONLY",
            siteKey(), kind, at)
         : store.query("SELECT * FROM {change_event} WHERE site_key = ? AND kind = ? AND component = ? AND observed_at <= ? ORDER BY last_seen_at DESC, id DESC FETCH FIRST 1 ROWS ONLY",
            siteKey(), kind, component, at);
      return (rows.isEmpty() ? null : rows.get(0));
   }



   /*******************************************************************************
    ** Attribute at execution, not queue time (a deployment may happen meanwhile).
    *******************************************************************************/
   public void attribute(long runId, Instant now) throws SQLException
   {
      Map<String, Object> build = latest("FRONTEND_DEPLOY", null, now);
      Map<String, Object> cdn = latest("CDN_STAGE", URI.create(config.getSiteBaseUrl()).getHost(), now);
      List<Map<String, Object>> cause = store.query("SELECT id FROM {change_event} WHERE site_key = ? AND observed_at <= ? ORDER BY observed_at DESC, id DESC FETCH FIRST 1 ROWS ONLY",
         siteKey(), now);
      store.update("UPDATE {lab_run} SET deployment_id = ?, cdn_stage = ?, change_event_id = ? WHERE id = ?",
         build == null ? null : build.get("to_value"), cdn == null ? null : cdn.get("to_value"), cause.isEmpty() ? null : cause.get(0).get("id"), runId);
   }



   /*******************************************************************************
    ** Same site identity as the tested pages.
    *******************************************************************************/
   String siteKey()
   {
      return ("host:" + URI.create(config.getSiteBaseUrl()).getHost().toLowerCase(Locale.ROOT));
   }
}
