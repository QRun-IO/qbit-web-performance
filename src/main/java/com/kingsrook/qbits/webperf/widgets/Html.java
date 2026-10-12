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

package com.kingsrook.qbits.webperf.widgets;


import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;


/*******************************************************************************
 ** HTML building blocks for the QBit's widgets. Next renders widget HTML
 ** through a sanitizer that keeps links and inline styles but drops style
 ** elements, so styling is inline and theme-neutral (inherited text color,
 ** translucent borders), and layouts are intrinsic: tiles wrap with
 ** flex-wrap, tables are full width with no declared column widths, long
 ** text wraps, nothing sets a fixed width or height or its own scrolling.
 ** Every dynamic value goes through escape().
 *******************************************************************************/
public final class Html
{
   public static final String GOOD   = "#16a34a";
   public static final String MEDIUM = "#d97706";
   public static final String POOR   = "#dc2626";
   public static final String MUTED  = "opacity:.75";

   private static final String WRAP     = "overflow-wrap:anywhere;min-width:0";
   private static final String TILE_ROW = "display:flex;flex-wrap:wrap;gap:12px";
   private static final String TILE     = "flex:1 1 150px;min-width:0;border:1px solid rgba(127,127,127,.25);border-radius:10px;padding:10px 12px";
   private static final String CELL     = "padding:6px 8px;border-bottom:1px solid rgba(127,127,127,.2);vertical-align:top;text-align:left";



   /*******************************************************************************
    ** Helpers only.
    *******************************************************************************/
   private Html()
   {
   }



   /*******************************************************************************
    ** Escape text for HTML.
    **
    ** @param value the value (null is empty)
    ** @return the escaped text
    *******************************************************************************/
   public static String escape(Object value)
   {
      return (Objects.toString(value, "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"));
   }



   /*******************************************************************************
    ** A link.
    **
    ** @param href the target
    ** @param text the text
    ** @return the HTML
    *******************************************************************************/
   public static String link(String href, Object text)
   {
      return ("<a href=\"" + escape(href) + "\" style=\"color:inherit;text-decoration:underline;text-underline-offset:2px;" + WRAP + "\">" + escape(text)
         + "</a>");
   }



   /*******************************************************************************
    ** Muted explanatory text.
    **
    ** @param text the text
    ** @return the HTML
    *******************************************************************************/
   public static String muted(String text)
   {
      return ("<div style=\"" + MUTED + ";font-size:13px;" + WRAP + "\">" + escape(text) + "</div>");
   }



   /*******************************************************************************
    ** The color of a 0-100 Lighthouse score (0-49 poor, 50-89 needs
    ** improvement, 90-100 good).
    **
    ** @param score the score
    ** @return the color
    *******************************************************************************/
   public static String scoreColor(int score)
   {
      return (score >= 90 ? GOOD : score >= 50 ? MEDIUM : POOR);
   }



   /*******************************************************************************
    ** A score with a colored dot and its band in words (color is never the
    ** only signal), or a dash.
    **
    ** @param score the score or null
    ** @return the HTML
    *******************************************************************************/
   public static String score(Integer score)
   {
      if(score == null)
      {
         return ("<span style=\"" + MUTED + "\">&ndash;</span>");
      }
      String band = score >= 90 ? "good" : score >= 50 ? "needs work" : "poor";
      return ("<span style=\"white-space:nowrap;font-variant-numeric:tabular-nums\"><span style=\"display:inline-block;width:.6em;height:.6em;border-radius:50%;"
         + "margin-right:.35em;background:" + scoreColor(score) + "\"></span><b>" + score + "</b> <span style=\"" + MUTED + ";font-size:12px\">" + band
         + "</span></span>");
   }



   /*******************************************************************************
    ** One tile: label, big value HTML, optional note.
    *******************************************************************************/
   public record Tile(String label, String valueHtml, String note)
   {
   }



   /*******************************************************************************
    ** A wrapping row of tiles.
    **
    ** @param tiles the tiles
    ** @return the HTML
    *******************************************************************************/
   public static String tiles(List<Tile> tiles)
   {
      StringBuilder html = new StringBuilder("<div style=\"" + TILE_ROW + "\">");
      for(Tile tile : tiles)
      {
         html.append("<div style=\"").append(TILE).append("\">")
            .append("<div style=\"font-size:12px;").append(MUTED).append(";text-transform:uppercase;letter-spacing:.04em\">").append(escape(tile.label()))
            .append("</div><div style=\"font-size:22px;line-height:1.4\">").append(tile.valueHtml()).append("</div>");
         if(tile.note() != null)
         {
            html.append("<div style=\"font-size:12px;").append(MUTED).append(";").append(WRAP).append("\">").append(escape(tile.note())).append("</div>");
         }
         html.append("</div>");
      }
      return (html.append("</div>").toString());
   }



   /*******************************************************************************
    ** A full-width table (header texts escaped, cells are HTML).
    **
    ** @param headers the column headers (at most four, per the readability rules)
    ** @param rows    the cells, as HTML
    ** @return the HTML
    *******************************************************************************/
   public static String table(List<String> headers, List<List<String>> rows)
   {
      StringBuilder html = new StringBuilder("<table style=\"width:100%;border-collapse:collapse;font-size:14px\"><thead><tr>");
      for(String header : headers)
      {
         html.append("<th style=\"").append(CELL).append(";font-size:12px;").append(MUTED).append(";font-weight:600\">").append(escape(header)).append("</th>");
      }
      html.append("</tr></thead><tbody>");
      for(List<String> row : rows)
      {
         html.append("<tr>");
         for(String cell : row)
         {
            html.append("<td style=\"").append(CELL).append(";").append(WRAP).append("\">").append(cell).append("</td>");
         }
         html.append("</tr>");
      }
      return (html.append("</tbody></table>").toString());
   }



   /*******************************************************************************
    ** "3 h ago" style age.
    **
    ** @param when the instant
    ** @param now  now
    ** @return the text
    *******************************************************************************/
   public static String ago(Instant when, Instant now)
   {
      if(when == null)
      {
         return ("never");
      }
      long seconds = Math.max(0, Duration.between(when, now).getSeconds());
      if(seconds < 120)
      {
         return ("just now");
      }
      if(seconds < 7200)
      {
         return ((seconds / 60) + " min ago");
      }
      if(seconds < 172800)
      {
         return ((seconds / 3600) + " h ago");
      }
      return ((seconds / 86400) + " days ago");
   }



   /*******************************************************************************
    ** Milliseconds as "1.84 s" or "320 ms", or a dash.
    **
    ** @param ms the value
    ** @return the text
    *******************************************************************************/
   public static String millis(Integer ms)
   {
      if(ms == null)
      {
         return ("–");
      }
      return (ms >= 1000 ? String.format(java.util.Locale.US, "%.2f s", ms / 1000.0) : ms + " ms");
   }



   /*******************************************************************************
    ** A server-drawn sparkline of 0-100 values (oldest first), as inline SVG
    ** that scales to its container.
    **
    ** @param values the values
    ** @return the SVG, or empty for fewer than two values
    *******************************************************************************/
   public static String sparkline(List<Integer> values)
   {
      if(values.size() < 2)
      {
         return ("");
      }
      StringBuilder points = new StringBuilder();
      for(int i = 0; i < values.size(); i++)
      {
         double x = 100.0 * i / (values.size() - 1);
         double y = 28 - 26.0 * values.get(i) / 100.0;
         points.append(String.format(java.util.Locale.US, "%.1f,%.1f ", x, y));
      }
      return ("<svg viewBox=\"0 0 100 30\" preserveAspectRatio=\"none\" style=\"width:100%;height:36px;display:block\" role=\"img\" aria-label=\"Trend of the last "
         + values.size() + " scores\"><polyline fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" vector-effect=\"non-scaling-stroke\" points=\""
         + points.toString().trim() + "\"/></svg>");
   }
}
