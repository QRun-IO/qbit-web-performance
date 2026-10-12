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

package com.kingsrook.qbits.webperf.store;


import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;


/*******************************************************************************
 ** The read queries behind the QBit's widgets. Every figure is a stored run,
 ** finding or field snapshot; nothing is estimated.
 *******************************************************************************/
public class WebPerfQueries
{
   /** The latest successful run of each page and strategy. */
   static final String LATEST_RUNS = """
      SELECT r.id, r.target_id, r.url, r.strategy, r.completed_at, r.lighthouse_version, r.score_performance, r.score_accessibility,
         r.score_best_practices, r.score_seo, r.lcp_ms, r.cls, r.tbt_ms, r.fcp_ms, t.tier, t.label, t.content_table, t.content_id
      FROM {lab_run} r JOIN {target} t ON t.id = r.target_id
      WHERE r.status = 'SUCCEEDED' AND t.enabled = TRUE
        AND r.id = (SELECT MAX(r2.id) FROM {lab_run} r2 WHERE r2.target_id = r.target_id AND r2.strategy = r.strategy AND r2.status = 'SUCCEEDED')""";

   private final WebPerfStore store;



   /*******************************************************************************
    ** Queries over a store.
    **
    ** @param store the store
    *******************************************************************************/
   public WebPerfQueries(WebPerfStore store)
   {
      this.store = store;
   }



   /*******************************************************************************
    ** The latest successful run of each enabled page and strategy.
    **
    ** @return the rows
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> latestRuns() throws SQLException
   {
      return (store.query(LATEST_RUNS + " ORDER BY r.score_performance, r.id"));
   }



   /*******************************************************************************
    ** Successful runs since an instant (for trends), oldest first.
    **
    ** @param since the instant
    ** @return completed_at, strategy, score_performance
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> successfulRunsSince(Instant since) throws SQLException
   {
      return (store.query("SELECT completed_at, strategy, score_performance FROM {lab_run} WHERE status = 'SUCCEEDED' AND completed_at >= ? ORDER BY completed_at",
         since));
   }



   /*******************************************************************************
    ** The findings of the latest successful runs, grouped by audit: title,
    ** pages affected, total estimated savings.
    **
    ** @param limit rows
    ** @return audit_id, title, pages, savings_ms
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> topOpportunities(int limit) throws SQLException
   {
      return (store.query("""
         SELECT f.audit_id, MAX(f.title) AS title, COUNT(DISTINCT f.target_id) AS pages, SUM(COALESCE(f.savings_ms, 0)) AS savings_ms
         FROM {lab_finding} f
         WHERE f.run_id IN (SELECT r.id FROM {lab_run} r WHERE r.status = 'SUCCEEDED'
            AND r.id = (SELECT MAX(r2.id) FROM {lab_run} r2 WHERE r2.target_id = r.target_id AND r2.strategy = r.strategy AND r2.status = 'SUCCEEDED'))
         GROUP BY f.audit_id
         ORDER BY COUNT(DISTINCT f.target_id) DESC, SUM(COALESCE(f.savings_ms, 0)) DESC, f.audit_id
         FETCH FIRST %d ROWS ONLY""".formatted(limit)));
   }



   /*******************************************************************************
    ** The newest runs.
    **
    ** @param limit rows
    ** @return the rows
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> recentRuns(int limit) throws SQLException
   {
      return (store.query("""
         SELECT id, target_id, url, strategy, status, trigger_type, queued_at, completed_at, score_performance, score_accessibility, score_best_practices,
            score_seo, error_message
         FROM {lab_run} WHERE status <> 'SKIPPED_QUOTA' ORDER BY id DESC FETCH FIRST %d ROWS ONLY""".formatted(limit)));
   }



   /*******************************************************************************
    ** The findings of a run, largest estimated savings first.
    **
    ** @param runId the run
    ** @param limit rows
    ** @return the rows
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> findingsOf(long runId, int limit) throws SQLException
   {
      return (store.query("""
         SELECT id, title, kind, category, display_value, savings_ms, savings_bytes, top_items FROM {lab_finding} WHERE run_id = ?
         ORDER BY CASE WHEN savings_ms IS NULL THEN 1 ELSE 0 END, savings_ms DESC, score, id FETCH FIRST %d ROWS ONLY""".formatted(limit), runId));
   }



   /*******************************************************************************
    ** The tested pages of a record (or the page itself).
    **
    ** @param tableName the record's table
    ** @param id        the record id
    ** @param direct    true when the record is a tested page
    ** @return id, url, enabled
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> pagesOf(String tableName, long id, boolean direct) throws SQLException
   {
      return (direct
         ? store.query("SELECT id, url, enabled FROM {target} WHERE id = ?", id)
         : store.query("SELECT id, url, enabled FROM {target} WHERE content_table = ? AND content_id = ? ORDER BY id", tableName, id));
   }



   /*******************************************************************************
    ** A page's runs on a strategy, newest first.
    **
    ** @param targetId the page
    ** @param strategy the strategy
    ** @param limit    rows
    ** @return the rows
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> runsOf(long targetId, String strategy, int limit) throws SQLException
   {
      return (store.query("""
         SELECT id, status, queued_at, completed_at, score_performance, score_accessibility, score_best_practices, score_seo, fcp_ms, lcp_ms, tbt_ms, cls,
            lcp_element, error_message, lighthouse_version
         FROM {lab_run} WHERE target_id = ? AND strategy = ? AND status <> 'SKIPPED_QUOTA' ORDER BY id DESC FETCH FIRST %d ROWS ONLY""".formatted(limit),
         targetId, strategy));
   }



   /*******************************************************************************
    ** The latest Google field data rows for a scope key (one per form factor).
    **
    ** @param scope URL or ORIGIN
    ** @param key   the URL or origin
    ** @return the rows, newest check first
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> fieldData(String scope, String key) throws SQLException
   {
      return (store.query("""
         SELECT form_factor, status, period_end, p75_lcp_ms, p75_inp_ms, p75_cls, p75_fcp_ms, p75_ttfb_ms, last_checked_at
         FROM {field_snapshot} WHERE scope = ? AND scope_key = ? ORDER BY last_checked_at DESC""", scope, key));
   }



   /*******************************************************************************
    ** How many runs wait in the queue.
    **
    ** @return the count
    ** @throws SQLException on failure
    *******************************************************************************/
   public int queuedCount() throws SQLException
   {
      return (WebPerfStore.asInt(store.query("SELECT COUNT(*) AS n FROM {lab_run} WHERE status IN ('QUEUED', 'RUNNING')").get(0).get("n")));
   }
}
