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


import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundleEntry;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.testing.HelpBundleAssertions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Validate the shipped bundle against the QBit's own metadata, without a host.
 *******************************************************************************/
class WebPerfHelpBundleTest
{
   /*******************************************************************************
    ** All default features are present; registration needs no helpContent table.
    *******************************************************************************/
   @Test
   void bundleCoversOwnedMetadata() throws Exception
   {
      QInstance qInstance = WebPerfTestSupport.newInstance();
      new WebPerfQBitProducer().withQBitConfig(WebPerfTestSupport.config()).produce(qInstance);

      assertThat(qInstance.getTable("helpContent")).isNull();
      assertThat(HelpBundles.of(qInstance).getBundles()).singleElement()
         .satisfies(bundle -> assertThat(bundle.getId()).isEqualTo("qbit-web-performance"));
      HelpBundleAssertions.forInstance(qInstance)
         .bundles("help/qbit-web-performance.yaml")
         .owning(target -> target.name().startsWith("web_performance_") || target.name().startsWith("webPerformance"))
         .assertComplete();

      // RC.7 validates supplied widget and lookup keys, but does not require their coverage.
      var keys = HelpBundles.of(qInstance).getBundles().get(0).getEntries().stream().map(HelpBundleEntry::getKey).toList();
      qInstance.getWidgets().keySet().stream().filter(name -> name.startsWith("webPerformance"))
         .forEach(name -> assertThat(keys).contains("widget:" + name + ";slot:label"));
      qInstance.getPossibleValueSources().keySet().stream().filter(name -> name.startsWith("web_performance_"))
         .forEach(name -> assertThat(keys).contains("possibleValueSource:" + name + ";slot:help"));
   }
}
