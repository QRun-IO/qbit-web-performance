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


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.webperf.metadata.WebPerfMetaData;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Entry point of the web performance QBit (QRun-IO/qqq
 ** docs/specs/2026-10-12-web-performance-design.md).
 **
 ** Host usage:
 ** <pre>
 ** new WebPerfQBitProducer()
 **    .withQBitConfig(new WebPerfQBitConfig()
 **       .withBackendName("rdbms")
 **       .withParentAppName("cms")
 **       .withSiteBaseUrl("https://www.example.com")
 **       .withUrlSource(new SitemapUrlSource("https://www.example.com/sitemap.xml"))
 **       .withDeployProbe(new HttpBuildIdProbe("FRONTEND", "http://frontend:3000/api/build"))
 **       .withGoogleApiKey(System.getenv("WEBPERF_GOOGLE_API_KEY")))
 **    .produce(qInstance);
 ** </pre>
 ** The tables come from the host-included changelog
 ** (db/liquibase/qbit-web-performance/web_performance.changelog.sql), whose
 ** web_performance.tablePrefix property must equal the config's prefix. The QBit
 ** never runs DDL.
 *******************************************************************************/
public class WebPerfQBitProducer implements QBitMetaDataProducer<WebPerfQBitConfig>
{
   private static final QLogger LOG = QLogger.getLogger(WebPerfQBitProducer.class);

   public static final String GROUP_ID    = "com.kingsrook.qbits";
   public static final String ARTIFACT_ID = "web-performance";
   public static final String VERSION     = "0.1.0";

   private WebPerfQBitConfig qBitConfig;
   private String            namespace;



   /*******************************************************************************
    ** Fluent setter for the config.
    **
    ** @param config the config
    ** @return this
    *******************************************************************************/
   public WebPerfQBitProducer withQBitConfig(WebPerfQBitConfig config)
   {
      this.qBitConfig = config;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the config.
    *******************************************************************************/
   @Override
   public WebPerfQBitConfig getQBitConfig()
   {
      return (qBitConfig);
   }



   /*******************************************************************************
    ** Fluent setter for the namespace (also the table prefix when the config
    ** keeps the default).
    **
    ** @param namespace the namespace
    ** @return this
    *******************************************************************************/
   public WebPerfQBitProducer withNamespace(String namespace)
   {
      this.namespace = namespace;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the namespace.
    *******************************************************************************/
   @Override
   public String getNamespace()
   {
      return (namespace);
   }



   /*******************************************************************************
    ** The QBit identity.
    *******************************************************************************/
   @Override
   public QBitMetaData getQBitMetaData()
   {
      return (new QBitMetaData()
         .withGroupId(GROUP_ID)
         .withArtifactId(ARTIFACT_ID)
         .withVersion(VERSION)
         .withNamespace(getNamespace())
         .withConfig(getQBitConfig()));
   }



   /*******************************************************************************
    ** A producer found by package scanning has no config: skip it.
    *******************************************************************************/
   @Override
   public boolean isEnabled()
   {
      return (qBitConfig != null);
   }



   /*******************************************************************************
    ** Validate the config, then add the QBit and its meta-data. Returns an
    ** empty output, so a host that also adds the output adds nothing twice.
    *******************************************************************************/
   @Override
   public MetaDataProducerMultiOutput produce(QInstance qInstance) throws QException
   {
      if(qBitConfig == null)
      {
         throw (new QException("Web performance configuration validation failed: no config was supplied (call withQBitConfig)"));
      }
      if(StringUtils.hasContent(namespace) && WebPerfQBitConfig.DEFAULT_TABLE_PREFIX.equals(qBitConfig.getTableNamePrefix()))
      {
         qBitConfig.setTableNamePrefix(namespace.endsWith("_") ? namespace : namespace + "_");
      }
      if(StringUtils.hasContent(namespace) && WebPerfQBitConfig.DEFAULT_NAME_PREFIX.equals(qBitConfig.getNamePrefix()))
      {
         qBitConfig.withNamePrefix(namespace.replaceAll("[^A-Za-z0-9]", ""));
      }

      List<String> errors = new ArrayList<>();
      qBitConfig.validate(qInstance, errors);
      for(String base : WebPerfSchema.TABLES)
      {
         if(qInstance.getTable(qBitConfig.applyPrefix(base)) != null)
         {
            errors.add("table " + qBitConfig.applyPrefix(base) + " already exists in the instance (another install needs another namespace)");
         }
      }
      if(qInstance.getProcess(qBitConfig.qualify(com.kingsrook.qbits.webperf.processes.WebPerfProcesses.SCHEDULED_SYNC)) != null)
      {
         errors.add("processes named " + qBitConfig.getNamePrefix() + "* already exist in the instance (another install needs another namespace)");
      }
      if(qInstance.getApp(qBitConfig.getAppName()) != null)
      {
         errors.add("app " + qBitConfig.getAppName() + " already exists in the instance");
      }
      if(!errors.isEmpty())
      {
         throw (new QException("Web performance configuration validation failed: " + String.join("; ", errors)));
      }

      qInstance.addQBit(getQBitMetaData());
      new WebPerfRuntime(qBitConfig).addSelfToInstance(qInstance);
      new WebPerfMetaData(qBitConfig).addTo(qInstance);
      com.kingsrook.qbits.webperf.metadata.WebPerfHelp.apply(qInstance, qBitConfig);
      // Registration is metadata-only; core guards startup sync when helpContent is absent.
      HelpBundles.register(qInstance, "help/qbit-web-performance.yaml");

      LOG.info("Web performance registered", logPair("tablePrefix", qBitConfig.getTableNamePrefix()), logPair("app", qBitConfig.getAppName()),
         logPair("siteBaseUrl", qBitConfig.getSiteBaseUrl()), logPair("features", qBitConfig.getFeatures()),
         logPair("googleApiKey", qBitConfig.hasGoogleApiKey() ? "configured" : "none: lab and CrUX tests are off"));
      return (new MetaDataProducerMultiOutput());
   }
}
