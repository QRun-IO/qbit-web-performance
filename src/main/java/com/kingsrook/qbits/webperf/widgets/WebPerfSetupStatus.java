/*
 * Copyright 2026 Kingsrook, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.webperf.widgets;


import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.webperf.WebPerfFeature;
import com.kingsrook.qbits.webperf.WebPerfQBitConfig;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qbits.webperf.store.WebPerfStore;


/*******************************************************************************
 ** What is set up on the Performance home and what is next: whether pages have
 ** been synced, whether budgets exist (and whether they are only the seeded
 ** defaults), and whether the lab runner and Google field data are configured.
 ** The checks use the runtime's own resolution (WebPerfRuntime.labRunner() and
 ** cruxClient(), the url source), so the card never says "needs setup" for
 ** something the runtime would in fact run.
 *******************************************************************************/
public final class WebPerfSetupStatus
{
   public static final String SET_UP      = "Set up";
   public static final String NEEDS_SETUP = "Needs setup";
   public static final String DEFAULTS    = "Using defaults";
   public static final String OPTIONAL    = "Optional";
   public static final String OFF         = "Off";



   /*******************************************************************************
    ** One line of the card.
    **
    ** @param name     what is being set up
    ** @param state    one of the state constants
    ** @param detail   what the state is (counts, and the next step)
    ** @param tableName the table to open (prefixed name), or null
    *******************************************************************************/
   public record Item(String name, String state, String detail, String tableName)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private WebPerfSetupStatus()
   {
   }



   /*******************************************************************************
    ** The card's lines, in setup order.
    *******************************************************************************/
   public static List<Item> items(WebPerfRuntime runtime) throws SQLException
   {
      WebPerfQBitConfig config = runtime.getConfig();
      WebPerfStore      store  = runtime.store();
      List<Item>        items  = new ArrayList<>();

      //////////////////////////////////////////////////////////////////////////
      // tested pages: synced from the url source (sitemap) or added by hand  //
      //////////////////////////////////////////////////////////////////////////
      long pages = count(store, "SELECT COUNT(*) AS n FROM {target}");
      String target = config.applyPrefix(WebPerfSchema.TARGET);
      if(pages > 0)
      {
         items.add(new Item("Tested pages", SET_UP, pages + " page" + (pages == 1 ? "" : "s") + " are tracked.", target));
      }
      else if(config.getUrlSource() == null)
      {
         items.add(new Item("Tested pages", NEEDS_SETUP, "No pages yet and no page source is configured. Add a page by hand, or have the host set a URL source (the sitemap) in the qbit config.", target));
      }
      else
      {
         items.add(new Item("Tested pages", NEEDS_SETUP, "No pages yet. Run “Sync pages from sitemap” on Tested Pages.", target));
      }

      //////////////////////////////////////////////////////////////////////////
      // lab runner: the configured one, else PageSpeed Insights with a key   //
      //////////////////////////////////////////////////////////////////////////
      if(!config.isOn(WebPerfFeature.LAB))
      {
         items.add(new Item("Lab tests (Lighthouse)", OFF, "The LAB feature is turned off in the qbit config.", null));
      }
      else if(runtime.labRunner() != null)
      {
         items.add(new Item("Lab tests (Lighthouse)", SET_UP, "A lab runner is configured. Use “Run site audit” on Tested Pages, or wait for the schedule.", config.applyPrefix(WebPerfSchema.LAB_RUN)));
      }
      else
      {
         items.add(new Item("Lab tests (Lighthouse)", NEEDS_SETUP, "No Google API key is configured, so lab tests cannot run. The host sets googleApiKey in the qbit config (read from an environment variable).", null));
      }

      //////////////////////////////////////////////////////////////////////////
      // budgets: the seeded reference budgets, or the admin's own            //
      //////////////////////////////////////////////////////////////////////////
      long budgets  = count(store, "SELECT COUNT(*) AS n FROM {budget} WHERE enabled = TRUE");
      long custom   = count(store, "SELECT COUNT(*) AS n FROM {budget} WHERE enabled = TRUE AND (seeded IS NULL OR seeded = FALSE)");
      String budget = config.applyPrefix(WebPerfSchema.BUDGET);
      if(budgets == 0)
      {
         items.add(new Item("Budgets", NEEDS_SETUP, "No budget is enabled, so nothing is flagged as too slow. Add one, or enable the reference budgets.", budget));
      }
      else if(custom == 0)
      {
         items.add(new Item("Budgets", DEFAULTS, budgets + " reference budgets (Google's “good” thresholds) are enabled. Edit them or add your own.", budget));
      }
      else
      {
         items.add(new Item("Budgets", SET_UP, budgets + " budgets are enabled, " + custom + " of them your own.", budget));
      }

      //////////////////////////////////////////////////////////////////////////
      // Google field data (CrUX)                                             //
      //////////////////////////////////////////////////////////////////////////
      if(!config.isOn(WebPerfFeature.FIELD_CRUX))
      {
         items.add(new Item("Google field data", OFF, "The FIELD_CRUX feature is turned off in the qbit config.", null));
      }
      else if(runtime.cruxClient() != null)
      {
         items.add(new Item("Google field data", SET_UP, "Checked daily; use “Refresh Google field data” to check now.", config.applyPrefix(WebPerfSchema.FIELD_SNAPSHOT)));
      }
      else
      {
         items.add(new Item("Google field data", NEEDS_SETUP, "No Google API key is configured, so Google field data is not checked.", null));
      }

      items.add(new Item("Regressions, changes, comparisons", OPTIONAL, "Filled in by the qbit as tests run and deploys are detected. Nothing to set up.", config.applyPrefix(WebPerfSchema.REGRESSION)));
      return (items);
   }



   /*******************************************************************************
    ** The card's HTML.
    *******************************************************************************/
   public static String html(WebPerfRuntime runtime) throws SQLException
   {
      List<List<String>> rows = new ArrayList<>();
      for(Item item : items(runtime))
      {
         String color = NEEDS_SETUP.equals(item.state()) ? Html.POOR : SET_UP.equals(item.state()) ? Html.GOOD : Html.MEDIUM;
         rows.add(List.of(
            item.tableName() == null ? Html.escape(item.name()) : Html.link("/app/" + item.tableName(), item.name()),
            "<b style=\"color:" + color + "\">" + Html.escape(item.state()) + "</b>",
            Html.escape(item.detail())));
      }
      return (Html.table(List.of("Part", "State", "What is next"), rows));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static long count(WebPerfStore store, String sql) throws SQLException
   {
      Long n = WebPerfStore.asLong(store.query(sql).get(0).get("n"));
      return (n == null ? 0 : n);
   }

}
