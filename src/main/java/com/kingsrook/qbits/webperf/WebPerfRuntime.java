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


import com.kingsrook.qbits.webperf.field.CruxClient;
import com.kingsrook.qbits.webperf.lab.PsiLabRunner;
import com.kingsrook.qbits.webperf.service.WebPerfService;
import com.kingsrook.qbits.webperf.spi.LabRunner;
import com.kingsrook.qbits.webperf.store.WebPerfStore;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.QSupplementalInstanceMetaData;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;


/*******************************************************************************
 ** One install's runtime state on a QInstance (supplemental meta-data named
 ** "webPerformance.&lt;prefix&gt;", so two namespaced installs never collide):
 ** its config, store and service. Process steps find it through the
 ** "webPerformanceInstance" value every QBit process carries; widgets through their
 ** default values.
 *******************************************************************************/
public class WebPerfRuntime implements QSupplementalInstanceMetaData
{
   public static final String NAME_PREFIX    = "webPerformance.";
   public static final String INSTANCE_FIELD = "webPerformanceInstance";

   private final WebPerfQBitConfig config;



   /*******************************************************************************
    ** Runtime for a validated config.
    **
    ** @param config the config
    *******************************************************************************/
   public WebPerfRuntime(WebPerfQBitConfig config)
   {
      this.config = config;
   }



   /*******************************************************************************
    ** The runtime of an install on an instance.
    **
    ** @param qInstance   the instance
    ** @param tablePrefix the install's table prefix
    ** @return the runtime
    ** @throws IllegalStateException when not registered
    *******************************************************************************/
   public static WebPerfRuntime of(QInstance qInstance, String tablePrefix)
   {
      WebPerfRuntime runtime = qInstance == null ? null : QSupplementalInstanceMetaData.of(qInstance, NAME_PREFIX + tablePrefix);
      if(runtime == null)
      {
         throw (new IllegalStateException("qbit-web-performance (" + tablePrefix + ") is not registered on this QInstance"));
      }
      return (runtime);
   }



   /*******************************************************************************
    ** The runtime a process step belongs to (its webPerformanceInstance value).
    **
    ** @param input the step input
    ** @return the runtime
    *******************************************************************************/
   public static WebPerfRuntime forInput(RunBackendStepInput input)
   {
      return (of(QContext.getQInstance(), input.getValueString(INSTANCE_FIELD)));
   }



   /*******************************************************************************
    ** The store on the current instance's backend.
    **
    ** @return the store
    *******************************************************************************/
   public WebPerfStore store()
   {
      return (store(QContext.getQInstance()));
   }



   /*******************************************************************************
    ** The store on an instance's backend.
    **
    ** @param qInstance the instance
    ** @return the store
    *******************************************************************************/
   public WebPerfStore store(QInstance qInstance)
   {
      RDBMSBackendMetaData backend = (RDBMSBackendMetaData) qInstance.getBackend(config.getBackendName());
      return (new WebPerfStore(config, () -> ConnectionManager.getConnection(backend)));
   }



   /*******************************************************************************
    ** The service on the current instance.
    **
    ** @return the service
    *******************************************************************************/
   public WebPerfService service()
   {
      return (new WebPerfService(this, store()));
   }



   /*******************************************************************************
    ** The lab runner: the configured one, else PageSpeed Insights with the
    ** Google API key, else null (no key: lab tests are off).
    **
    ** @return the runner or null
    *******************************************************************************/
   public LabRunner labRunner()
   {
      if(config.getLabRunner() != null)
      {
         return (config.getLabRunner());
      }
      return (config.hasGoogleApiKey() ? new PsiLabRunner(config.getGoogleApiKey()) : null);
   }



   /*******************************************************************************
    ** The CrUX client: the configured one, else Google's with the API key,
    ** else null (no key: field data checks are off).
    **
    ** @return the client or null
    *******************************************************************************/
   public CruxClient cruxClient()
   {
      if(config.getCruxClient() != null)
      {
         return (config.getCruxClient());
      }
      return (config.hasGoogleApiKey() ? new CruxClient(config.getGoogleApiKey()) : null);
   }



   /*******************************************************************************
    ** Name of this supplemental meta-data.
    *******************************************************************************/
   @Override
   public String getName()
   {
      return (NAME_PREFIX + config.getTableNamePrefix());
   }



   /*******************************************************************************
    ** Add to an instance.
    *******************************************************************************/
   @Override
   public void addSelfToInstance(QInstance qInstance)
   {
      qInstance.withSupplementalMetaData(this);
   }



   /*******************************************************************************
    ** Getter for config.
    **
    ** @return the config
    *******************************************************************************/
   public WebPerfQBitConfig getConfig()
   {
      return (config);
   }
}
