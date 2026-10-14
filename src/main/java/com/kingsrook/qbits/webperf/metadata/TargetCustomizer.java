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

package com.kingsrook.qbits.webperf.metadata;


import java.net.URI;
import java.util.List;
import java.util.Optional;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qqq.backend.core.actions.customizers.AbstractPreInsertCustomizer;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.statusmessages.BadInputStatusMessage;


/*******************************************************************************
 ** Validate manually entered URLs and derive the fields used by sync and tests.
 *******************************************************************************/
public class TargetCustomizer implements TableCustomizerInterface
{
   /*******************************************************************************
    ** Derive site identity before the unique site/URL check.
    *******************************************************************************/
   @Override
   public AbstractPreInsertCustomizer.WhenToRun whenToRunPreInsert(InsertInput input, boolean isPreview)
   {
      return (AbstractPreInsertCustomizer.WhenToRun.BEFORE_ALL_VALIDATIONS);
   }



   /*******************************************************************************
    ** Manual pages need only a URL, label, tier and enabled flag from the user.
    *******************************************************************************/
   @Override
   public List<QRecord> preInsert(InsertInput input, List<QRecord> records, boolean isPreview)
   {
      validate(input.getTableName(), records, true);
      return (records);
   }



   /*******************************************************************************
    ** Keep URL edits subject to the same validation and refresh their path.
    *******************************************************************************/
   @Override
   public List<QRecord> preUpdate(UpdateInput input, List<QRecord> records, boolean isPreview, Optional<List<QRecord>> oldRecords)
   {
      validate(input.getTableName(), records, false);
      return (records);
   }



   /*******************************************************************************
    ** Accept absolute HTTPS URLs without embedded credentials or fragments.
    *******************************************************************************/
   private void validate(String tableName, List<QRecord> records, boolean inserting)
   {
      String prefix = tableName.substring(0, tableName.length() - WebPerfSchema.TARGET.length());
      WebPerfRuntime runtime = WebPerfRuntime.of(QContext.getQInstance(), prefix);
      for(QRecord record : records)
      {
         if(!inserting && !record.getValues().containsKey("url"))
         {
            continue;
         }
         String url = record.getValueString("url");
         try
         {
            URI uri = URI.create(url == null ? "" : url);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            {
               throw (new IllegalArgumentException("Invalid page URL"));
            }
            record.setValue("path", uri.getPath().isEmpty() ? "/" : uri.getPath());
            if(inserting)
            {
               record.setValue("siteKey", runtime.service().siteKey());
               record.setValue("source", "MANUAL");
               record.setValue("inSitemap", false);
            }
         }
         catch(IllegalArgumentException e)
         {
            record.addError(new BadInputStatusMessage("URL must be an absolute HTTPS URL without credentials or a fragment."));
         }
      }
   }
}
