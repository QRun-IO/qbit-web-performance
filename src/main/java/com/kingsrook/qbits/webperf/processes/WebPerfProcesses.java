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

package com.kingsrook.qbits.webperf.processes;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.kingsrook.qbits.webperf.WebPerfFeature;
import com.kingsrook.qbits.webperf.WebPerfQBitConfig;
import com.kingsrook.qbits.webperf.WebPerfRuntime;
import com.kingsrook.qbits.webperf.WebPerfSchema;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractExtractStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** The QBit's processes (design §6.1). None is in any app's nav:
 **
 ** - record and table actions, each a standard streamed ETL process (record
 **   input, Review summary lines, Result summary lines):
 **   "Test page speed" (tested pages, and every configured content table),
 **   "Run site audit" (tested pages), "Sync pages from sitemap" (tested
 **   pages), "Retry" (lab runs), "Refresh Google field data" (field data);
 ** - hidden scheduled processes: the queue worker (every minute), the daily
 **   KEY-page tests, the weekly full audit, the daily sitemap sync and the
 **   daily CrUX refresh. They exist without a scheduler and run only with one.
 **
 ** Permissions: actions that queue tests need "&lt;base&gt;.test.hasAccess",
 ** sync and field data need "&lt;base&gt;.admin.hasAccess" (base "webperf").
 *******************************************************************************/
public final class WebPerfProcesses
{
   public static final String TEST_PAGE_SPEED     = "TestPageSpeed";
   public static final String RUN_SITE_AUDIT      = "RunSiteAudit";
   public static final String SYNC_TARGETS        = "SyncPages";
   public static final String RETRY_RUNS          = "RetryRuns";
   public static final String REFRESH_FIELD_DATA  = "RefreshFieldData";
   public static final String RUN_QUEUED_TESTS    = "RunQueuedTests";
   public static final String SCHEDULE_KEY_TESTS  = "ScheduleKeyTests";
   public static final String SCHEDULE_SITE_AUDIT = "ScheduleSiteAudit";
   public static final String SCHEDULED_SYNC      = "ScheduledSync";
   public static final String SCHEDULED_FIELD     = "ScheduledFieldData";

   /** Process value naming the trigger recorded on queued runs. */
   public static final String TRIGGER_FIELD = "webperfTrigger";

   public static final int    WORKER_REPEAT_SECONDS = 60;
   public static final String KEY_TESTS_CRON        = "0 30 3 * * ?";
   public static final String SITE_AUDIT_CRON       = "0 0 4 ? * SUN";
   public static final String SYNC_CRON             = "0 45 2 * * ?";
   public static final String FIELD_CRON            = "0 0 6 * * ?";



   /*******************************************************************************
    ** Processes only.
    *******************************************************************************/
   private WebPerfProcesses()
   {
   }



   /*******************************************************************************
    ** Every process of an install.
    **
    ** @param config    the config
    ** @param qInstance the instance (for content table labels)
    ** @return the processes
    *******************************************************************************/
   public static List<QProcessMetaData> define(WebPerfQBitConfig config, QInstance qInstance)
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      String                 targets   = config.applyPrefix(WebPerfSchema.TARGET);

      if(config.isOn(WebPerfFeature.LAB))
      {
         processes.add(etl(config, TEST_PAGE_SPEED, "Test page speed", "speed", targets, targets, ExtractViaQueryStep.class, QueueTestsSteps.Transform.class,
            QueueTestsSteps.Load.class, "MANUAL", "test", 1, "This is a preview of the pages that will be tested."));
         for(String table : config.getContentTables())
         {
            processes.add(etl(config, TEST_PAGE_SPEED + capitalize(table), "Test page speed", "speed", table, targets, ExtractViaQueryStep.class,
               QueueTestsSteps.Transform.class, QueueTestsSteps.Load.class, "MANUAL", "test", 1, "This is a preview of the records whose pages will be tested."));
         }
         processes.add(etl(config, RUN_SITE_AUDIT, "Run site audit", "fact_check", targets, targets, ExtractViaQueryStep.class, QueueTestsSteps.Transform.class,
            QueueTestsSteps.Load.class, "SITE_AUDIT", "test", null, "This is a preview of the pages the audit will test (enabled pages only)."));
         processes.add(etl(config, RETRY_RUNS, "Retry", "replay", config.applyPrefix(WebPerfSchema.LAB_RUN), config.applyPrefix(WebPerfSchema.LAB_RUN),
            ExtractViaQueryStep.class, RetryRunsSteps.Transform.class, RetryRunsSteps.Load.class, "MANUAL", "test", 1,
            "This is a preview of the runs to retry."));
      }
      processes.add(etl(config, SYNC_TARGETS, "Sync pages from sitemap", "sync", targets, targets, SyncPagesSteps.Extract.class, SyncPagesSteps.Transform.class,
         SyncPagesSteps.Load.class, null, "admin", null, "This is the list of pages the URL source returned."));
      if(config.isOn(WebPerfFeature.FIELD_CRUX))
      {
         String field = config.applyPrefix(WebPerfSchema.FIELD_SNAPSHOT);
         processes.add(etl(config, REFRESH_FIELD_DATA, "Refresh Google field data", "public", field, field, FieldDataSteps.Extract.class,
            FieldDataSteps.Transform.class, FieldDataSteps.Load.class, null, "admin", null, "These are the origin and pages Google field data will be checked for."));
      }

      if(config.isOn(WebPerfFeature.LAB))
      {
         processes.add(scheduled(config, RUN_QUEUED_TESTS, "Run queued page speed tests", ScheduledSteps.RunQueued.class,
            new QScheduleMetaData().withRepeatSeconds(WORKER_REPEAT_SECONDS).withInitialDelaySeconds(WORKER_REPEAT_SECONDS)));
         processes.add(scheduled(config, SCHEDULE_KEY_TESTS, "Test key pages", ScheduledSteps.KeyTests.class,
            new QScheduleMetaData().withCronExpression(KEY_TESTS_CRON).withCronTimeZoneId(config.getTimeZone())));
         processes.add(scheduled(config, SCHEDULE_SITE_AUDIT, "Weekly site audit", ScheduledSteps.SiteAudit.class,
            new QScheduleMetaData().withCronExpression(SITE_AUDIT_CRON).withCronTimeZoneId(config.getTimeZone())));
      }
      processes.add(scheduled(config, SCHEDULED_SYNC, "Sync pages from sitemap (scheduled)", ScheduledSteps.Sync.class,
         new QScheduleMetaData().withCronExpression(SYNC_CRON).withCronTimeZoneId(config.getTimeZone())));
      if(config.isOn(WebPerfFeature.FIELD_CRUX))
      {
         processes.add(scheduled(config, SCHEDULED_FIELD, "Refresh Google field data (scheduled)", ScheduledSteps.FieldData.class,
            new QScheduleMetaData().withCronExpression(FIELD_CRON).withCronTimeZoneId("UTC")));
      }
      return (processes);
   }



   /*******************************************************************************
    ** A streamed ETL action.
    *******************************************************************************/
   private static QProcessMetaData etl(WebPerfQBitConfig config, String name, String label, String icon, String sourceTable, String destinationTable,
                                       Class<? extends AbstractExtractStep> extract, Class<? extends AbstractTransformStep> transform,
                                       Class<? extends AbstractLoadStep> load, String trigger, String permission, Integer minRecords, String preview)
   {
      QProcessMetaData process = StreamedETLWithFrontendProcess.processMetaDataBuilder()
         .withName(config.qualify(name))
         .withLabel(label)
         .withIcon(new QIcon(icon))
         .withTableName(sourceTable)
         .withSourceTable(sourceTable)
         .withDestinationTable(destinationTable)
         .withExtractStepClass(extract)
         .withTransformStepClass(transform)
         .withLoadStepClass(load)
         .withSupportsFullValidation(true)
         .withDoFullValidation(true)
         .withPreviewMessage(preview)
         .withMinInputRecords(minRecords)
         .withFields(List.of(
            new QFieldMetaData(WebPerfRuntime.INSTANCE_FIELD, QFieldType.STRING).withDefaultValue(config.getTableNamePrefix()),
            new QFieldMetaData(TRIGGER_FIELD, QFieldType.STRING).withDefaultValue(trigger)))
         .getProcessMetaData();
      process.setPermissionRules(rules(config, permission));
      return (process);
   }



   /*******************************************************************************
    ** A hidden process with one backend step and, with a scheduler, a schedule.
    *******************************************************************************/
   private static QProcessMetaData scheduled(WebPerfQBitConfig config, String name, String label, Class<?> step, QScheduleMetaData schedule)
   {
      QProcessMetaData process = new QProcessMetaData()
         .withName(config.qualify(name))
         .withLabel(label)
         .withIsHidden(true)
         .withIcon(new QIcon("schedule"))
         .withPermissionRules(rules(config, "admin"))
         .withStep(new QBackendStepMetaData()
            .withName("run")
            .withCode(new QCodeReference(step))
            .withInputData(new QFunctionInputMetaData()
               .withField(new QFieldMetaData(WebPerfRuntime.INSTANCE_FIELD, QFieldType.STRING).withDefaultValue(config.getTableNamePrefix()))));
      if(config.getSchedulerName() != null)
      {
         process.withSchedule(schedule.withSchedulerName(config.getSchedulerName()));
      }
      return (process);
   }



   /*******************************************************************************
    ** HAS_ACCESS permission "&lt;base&gt;.&lt;suffix&gt;.hasAccess".
    *******************************************************************************/
   private static QPermissionRules rules(WebPerfQBitConfig config, String suffix)
   {
      return (new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION).withPermissionBaseName(config.getPermissionBaseName() + "." + suffix));
   }



   /*******************************************************************************
    ** "blog_post" to "BlogPost".
    *******************************************************************************/
   static String capitalize(String table)
   {
      StringBuilder result = new StringBuilder();
      for(String part : table.split("[_\\-]"))
      {
         if(!part.isEmpty())
         {
            result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
         }
      }
      return (result.toString());
   }
}
