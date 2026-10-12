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


import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.webperf.FixtureServer;
import com.kingsrook.qbits.webperf.field.CruxClient;
import com.kingsrook.qbits.webperf.spi.LabRunner;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** The PSI parser on a real Lighthouse 13 result of www.kof22.com, the PSI
 ** runner and the CrUX client over real HTTP (a local server standing in for
 ** Google), including that the API key never leaks into errors or strings.
 *******************************************************************************/
class LabClientsTest
{
   /*******************************************************************************
    ** Scores, metrics, LCP element and findings of the real fixture.
    *******************************************************************************/
   @Test
   void testParseRealLighthouseResult() throws Exception
   {
      LabResultParser.ParsedLab parsed = LabResultParser.parse(FixtureServer.fixture("psi-kof22-home-mobile.json"));
      assertEquals("13.5.0", parsed.lighthouseVersion());
      assertEquals("https://www.kof22.com/", parsed.finalUrl());
      assertEquals(81, parsed.score("PERFORMANCE"));
      assertEquals(82, parsed.score("ACCESSIBILITY"));
      assertEquals(100, parsed.score("BEST_PRACTICES"));
      assertEquals(92, parsed.score("SEO"));
      assertEquals(1074, parsed.fcpMs());
      assertEquals(2268, parsed.lcpMs());
      assertEquals(82, parsed.tbtMs());
      assertEquals(0.3507, parsed.cls());
      assertEquals(1312, parsed.speedIndexMs());
      assertEquals("div.mx-auto > div.grid > div.flex > p.mt-4", parsed.lcpElement());
      assertNull(parsed.runtimeError());
      assertTrue(parsed.fieldData().isEmpty(), "Google has no CrUX data for the site, and the parser invents none");
      assertThat(parsed.chromeUserAgent()).contains("HeadlessChrome");

      List<String> ids = parsed.findings().stream().map(LabResultParser.Finding::auditId).toList();
      assertThat(ids).contains("unused-javascript", "cls-culprits-insight", "render-blocking-insight", "color-contrast", "button-name", "meta-description")
         .doesNotContain("cumulative-layout-shift", "largest-contentful-paint")
         .doesNotHaveDuplicates();

      LabResultParser.Finding unused = finding(parsed, "unused-javascript");
      assertEquals("OPPORTUNITY", unused.kind());
      assertEquals("PERFORMANCE", unused.category());
      assertEquals(150, unused.savingsMs());
      assertEquals(22121L, unused.savingsBytes());
      assertThat(unused.metricSavings()).contains("LCP");
      assertThat(unused.topItems()).contains("https://www.kof22.com/_next/static/chunks/");
      assertEquals("INSIGHT", finding(parsed, "cls-culprits-insight").kind());
      LabResultParser.Finding contrast = finding(parsed, "color-contrast");
      assertEquals("FAILED_AUDIT", contrast.kind());
      assertEquals("ACCESSIBILITY", contrast.category());
      assertThat(contrast.topItems()).contains("button.font-mono");
   }



   /*******************************************************************************
    ** A runtime error and PSI field data (loadingExperience).
    *******************************************************************************/
   @Test
   void testRuntimeErrorAndFieldData()
   {
      LabResultParser.ParsedLab parsed = LabResultParser.parse("""
         {"loadingExperience": {"id": "https://www.example.com/a", "origin_fallback": true, "metrics": {
             "LARGEST_CONTENTFUL_PAINT_MS": {"percentile": 2100}, "CUMULATIVE_LAYOUT_SHIFT_SCORE": {"percentile": 5},
             "INTERACTION_TO_NEXT_PAINT": {"percentile": 120}}},
          "lighthouseResult": {"lighthouseVersion": "13.0.1", "runtimeError": {"code": "NO_FCP", "message": "The page did not paint any content."},
             "runWarnings": ["The page loaded too slowly"], "categories": {"performance": {"score": null}}, "audits": {}}}""");
      assertEquals("NO_FCP: The page did not paint any content.", parsed.runtimeError());
      assertEquals("The page loaded too slowly", parsed.runWarnings());
      assertNull(parsed.score("PERFORMANCE"));
      assertEquals(1, parsed.fieldData().size());
      LabResultParser.FieldData field = parsed.fieldData().get(0);
      assertEquals("ORIGIN", field.scope(), "an origin fallback is labelled as origin data");
      assertEquals(2100, field.p75LcpMs());
      assertEquals(0.05, field.p75Cls());
      assertEquals(120, field.p75InpMs());

      assertThatThrownBy(() -> LabResultParser.parse("{\"kind\": \"x\"}")).hasMessageContaining("no Lighthouse result");
   }



   /*******************************************************************************
    ** The PSI runner sends url, strategy, every category and the key, returns
    ** any status, and never shows the key.
    *******************************************************************************/
   @Test
   void testPsiRunner() throws Exception
   {
      try(FixtureServer google = new FixtureServer())
      {
         google.respond(new FixtureServer.Response(200, FixtureServer.fixture("psi-kof22-home-mobile.json")));
         PsiLabRunner runner = new PsiLabRunner(google.url("/pagespeedonline/v5/runPagespeed"), "AIzaSecretKey");
         LabRunner.LabResponse response = runner.run(new LabRunner.LabRequest("https://www.kof22.com/?a=b", "MOBILE",
            Set.of("PERFORMANCE", "ACCESSIBILITY", "BEST_PRACTICES", "SEO")));
         assertEquals(200, response.httpStatus());
         assertEquals(81, LabResultParser.parse(response.rawJson()).score("PERFORMANCE"));

         String query = google.getRequests().get(0).query();
         assertThat(query).contains("url=https%3A%2F%2Fwww.kof22.com%2F%3Fa%3Db", "strategy=MOBILE", "category=PERFORMANCE", "category=ACCESSIBILITY",
            "category=BEST_PRACTICES", "category=SEO", "key=AIzaSecretKey", "locale=en");
         assertEquals("PSI", runner.name());
         assertThat(runner.toString()).doesNotContain("AIzaSecretKey");

         google.respond(new FixtureServer.Response(429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\"}}"));
         assertEquals(429, runner.run(new LabRunner.LabRequest("https://www.kof22.com/", "DESKTOP", Set.of("SEO"))).httpStatus());
      }

      PsiLabRunner unreachable = new PsiLabRunner("http://127.0.0.1:1/runPagespeed", "AIzaSecretKey");
      assertThatThrownBy(() -> unreachable.run(new LabRunner.LabRequest("https://www.kof22.com/", "MOBILE", Set.of("SEO"))))
         .hasMessageContaining("https://www.kof22.com/").hasMessageNotContaining("AIzaSecretKey");
      assertThatThrownBy(() -> new PsiLabRunner("")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> unreachable.requestUrl(new LabRunner.LabRequest("https://x.example/", "TABLET", Set.of())))
         .isInstanceOf(IllegalArgumentException.class);
   }



   /*******************************************************************************
    ** CrUX: data is parsed (CLS from its string form), 404 is NO_DATA, other
    ** errors fail, the key never shows.
    *******************************************************************************/
   @Test
   void testCruxClient() throws Exception
   {
      try(FixtureServer google = new FixtureServer())
      {
         CruxClient client = new CruxClient(google.url("/v1/records:queryRecord"), "AIzaSecretKey");
         google.respond(new FixtureServer.Response(200, FixtureServer.fixture("crux-origin-phone.json")));
         CruxClient.CruxResult result = client.query("ORIGIN", "https://www.example.com", "PHONE");
         assertEquals("OK", result.status());
         assertEquals(2367, result.p75LcpMs());
         assertEquals(147, result.p75InpMs());
         assertEquals(0.05, result.p75Cls());
         assertEquals(1906, result.p75FcpMs());
         assertEquals(918, result.p75TtfbMs());
         assertEquals(LocalDate.of(2026, 9, 13), result.periodStart());
         assertEquals(LocalDate.of(2026, 10, 10), result.periodEnd());
         assertThat(result.distributions()).contains("largest_contentful_paint");
         assertThat(google.getRequests().get(0).body()).contains("\"origin\":\"https://www.example.com\"").contains("\"formFactor\":\"PHONE\"");
         assertThat(google.getRequests().get(0).query()).contains("key=AIzaSecretKey");

         google.respond(new FixtureServer.Response(404, "{\"error\":{\"code\":404,\"message\":\"chrome ux report data not found\",\"status\":\"NOT_FOUND\"}}"));
         CruxClient.CruxResult none = client.query("URL", "https://www.example.com/a", "ALL");
         assertEquals("NO_DATA", none.status());
         assertNull(none.p75LcpMs());
         assertThat(google.getRequests().get(1).body()).contains("\"url\"").doesNotContain("formFactor");

         google.respond(new FixtureServer.Response(403, "{}"));
         assertThatThrownBy(() -> client.query("ORIGIN", "https://www.example.com", "ALL")).hasMessageContaining("HTTP 403")
            .hasMessageNotContaining("AIzaSecretKey");
         assertThat(client.toString()).doesNotContain("AIzaSecretKey");
      }
   }



   /*******************************************************************************
    ** A finding by audit id.
    *******************************************************************************/
   private static LabResultParser.Finding finding(LabResultParser.ParsedLab parsed, String id)
   {
      return (parsed.findings().stream().filter(f -> f.auditId().equals(id)).findFirst().orElseThrow());
   }
}
