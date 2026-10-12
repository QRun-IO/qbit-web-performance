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


import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.kingsrook.qbits.webperf.field.CruxClient;
import com.kingsrook.qbits.webperf.spi.ChangeEventSource;
import com.kingsrook.qbits.webperf.spi.ContentResolver;
import com.kingsrook.qbits.webperf.spi.DeployProbe;
import com.kingsrook.qbits.webperf.spi.LabRunner;
import com.kingsrook.qbits.webperf.spi.RumSource;
import com.kingsrook.qbits.webperf.spi.UrlSource;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;


/*******************************************************************************
 ** The one config object of the web performance QBit (design §3.2). Defaults
 ** follow the owner decisions of 2026-10-12 (D1-D13): daily KEY pages, weekly
 ** full audit, at most 500 runs a day, mobile and desktop, all four
 ** Lighthouse categories, everything kept forever.
 **
 ** The Google API key (PageSpeed Insights and CrUX) is optional here: without
 ** it lab and CrUX tests are off and say so, and everything else works. It is
 ** never stored in a table and toString() redacts it.
 *******************************************************************************/
public class WebPerfQBitConfig implements QBitConfig
{
   private static final long serialVersionUID = 1L;

   public static final String DEFAULT_TABLE_PREFIX     = "web_performance_";
   public static final String DEFAULT_NAME_PREFIX      = "webPerformance";
   public static final String DEFAULT_APP_NAME         = "webPerformance";
   public static final String DEFAULT_APP_LABEL        = "Performance";
   public static final String DEFAULT_PERMISSION_BASE  = "webPerformance";
   public static final int    DEFAULT_MAX_RUNS_PER_DAY = 500;

   /** Google's documented PSI quota: 25,000 queries a day. */
   public static final int PSI_DAILY_QUOTA = 25_000;

   private static final Pattern PREFIX = Pattern.compile("^[a-z][a-z0-9_]{0,20}_$");
   private static final Pattern NAME   = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]{0,40}$");

   private String              backendName;
   private String              tableNamePrefix      = DEFAULT_TABLE_PREFIX;
   private String              namePrefix           = DEFAULT_NAME_PREFIX;
   private String              appName              = DEFAULT_APP_NAME;
   private String              appLabel             = DEFAULT_APP_LABEL;
   private String              parentAppName;
   private String              permissionBaseName   = DEFAULT_PERMISSION_BASE;
   private String              siteBaseUrl;
   private String              googleApiKey;
   private Set<WebPerfFeature> features             = defaultFeatures();
   private List<String>        strategies           = new ArrayList<>(List.of("MOBILE", "DESKTOP"));
   private List<String>        categories           = new ArrayList<>(List.of("PERFORMANCE", "ACCESSIBILITY", "BEST_PRACTICES", "SEO"));
   private List<String>        contentTables        = new ArrayList<>();
   private List<String>        keyPaths             = new ArrayList<>(List.of("/"));
   private int                 concurrency          = 2;
   private int                 maxRunsPerDay        = DEFAULT_MAX_RUNS_PER_DAY;
   private int                 minSpacingMillis     = 2000;
   private int                 cruxSpacingMillis    = 400;
   private int                 publishSettleSeconds = 180;
   private int                 deploySettleSeconds  = 300;
   private int                 reportRetentionDays  = 0;
   private String              schedulerName;
   private String              timeZone             = "UTC";

   private transient UrlSource               urlSource;
   private transient ContentResolver         contentResolver;
   private transient LabRunner               labRunner;
   private transient CruxClient              cruxClient;
   private transient RumSource               rumSource;
   private transient List<DeployProbe>       deployProbes       = new ArrayList<>();
   private transient List<ChangeEventSource> changeEventSources = new ArrayList<>();



   /*******************************************************************************
    ** Every feature except the optional Prometheus gauges.
    **
    ** @return the default feature set
    *******************************************************************************/
   public static Set<WebPerfFeature> defaultFeatures()
   {
      Set<WebPerfFeature> set = EnumSet.noneOf(WebPerfFeature.class);
      for(WebPerfFeature feature : WebPerfFeature.values())
      {
         if(feature.isDefault())
         {
            set.add(feature);
         }
      }
      return (set);
   }



   /*******************************************************************************
    ** Validate against the instance.
    *******************************************************************************/
   @Override
   public void validate(QInstance qInstance, List<String> errors)
   {
      QBackendMetaData backend = backendName == null ? null : qInstance.getBackend(backendName);
      assertCondition(backendName != null, "backendName is required", errors);
      assertCondition(backendName == null || backend != null, "backend " + backendName + " is not defined in the instance", errors);
      assertCondition(backend == null || backend instanceof RDBMSBackendMetaData, "backend " + backendName + " must be an RDBMS backend", errors);

      assertCondition(tableNamePrefix != null && PREFIX.matcher(tableNamePrefix).matches(),
         "tableNamePrefix must be lower case letters, digits and underscores, ending in an underscore (got " + tableNamePrefix + ")", errors);
      assertCondition(namePrefix != null && NAME.matcher(namePrefix).matches(), "namePrefix must be alphanumeric (got " + namePrefix + ")", errors);
      assertCondition(appName != null && NAME.matcher(appName).matches(), "appName must be alphanumeric (got " + appName + ")", errors);
      assertCondition(parentAppName == null || qInstance.getApp(parentAppName) != null, "parent app " + parentAppName + " is not defined", errors);

      assertCondition(isAbsoluteHttpUrl(siteBaseUrl), "siteBaseUrl must be an absolute http(s) URL (got " + siteBaseUrl + ")", errors);

      assertCondition(features != null, "features must not be null", errors);
      assertCondition(strategies != null && !strategies.isEmpty() && List.of("MOBILE", "DESKTOP").containsAll(strategies),
         "strategies must be MOBILE and/or DESKTOP", errors);
      assertCondition(categories != null && !categories.isEmpty()
         && List.of("PERFORMANCE", "ACCESSIBILITY", "BEST_PRACTICES", "SEO").containsAll(categories),
         "categories must be PERFORMANCE, ACCESSIBILITY, BEST_PRACTICES and/or SEO", errors);

      assertCondition(concurrency >= 1 && concurrency <= 10, "concurrency must be 1 to 10", errors);
      assertCondition(maxRunsPerDay >= 1 && maxRunsPerDay <= PSI_DAILY_QUOTA, "maxRunsPerDay must be 1 to " + PSI_DAILY_QUOTA, errors);
      assertCondition(minSpacingMillis >= 0, "minSpacingMillis must not be negative", errors);
      assertCondition(publishSettleSeconds >= 0 && deploySettleSeconds >= 0, "settle delays must not be negative", errors);
      assertCondition(reportRetentionDays >= 0, "reportRetentionDays must be 0 (keep forever) or more", errors);
      assertCondition(isZone(timeZone), "timeZone is not a valid zone id: " + timeZone, errors);

      assertCondition(keyPaths != null && keyPaths.stream().allMatch(p -> p != null && p.startsWith("/")), "keyPaths must be paths starting with /", errors);
      for(String table : contentTables)
      {
         assertCondition(qInstance.getTable(table) != null, "content table " + table + " is not defined", errors);
      }
   }



   /*******************************************************************************
    ** The default backend for the QBit's tables.
    *******************************************************************************/
   @Override
   public String getDefaultBackendNameForTables()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** A table base name with the prefix.
    **
    ** @param baseName e.g. "target"
    ** @return e.g. "web_performance_target"
    *******************************************************************************/
   public String applyPrefix(String baseName)
   {
      return (tableNamePrefix + baseName);
   }



   /*******************************************************************************
    ** A process, widget or app-level name with the name prefix.
    **
    ** @param name e.g. "TestPageSpeed"
    ** @return e.g. "webPerformanceTestPageSpeed"
    *******************************************************************************/
   public String qualify(String name)
   {
      return (namePrefix + name);
   }



   /*******************************************************************************
    ** Whether a feature is on.
    **
    ** @param feature the feature
    ** @return true when on
    *******************************************************************************/
   public boolean isOn(WebPerfFeature feature)
   {
      return (features != null && features.contains(feature));
   }



   /*******************************************************************************
    ** Whether lab and CrUX tests can run (a Google API key is configured).
    **
    ** @return true with a key
    *******************************************************************************/
   public boolean hasGoogleApiKey()
   {
      return (googleApiKey != null && !googleApiKey.isBlank());
   }



   /*******************************************************************************
    ** The config without secrets.
    *******************************************************************************/
   @Override
   public String toString()
   {
      return ("WebPerfQBitConfig{backendName=" + backendName + ", tableNamePrefix=" + tableNamePrefix + ", appName=" + appName
         + ", parentAppName=" + parentAppName + ", siteBaseUrl=" + siteBaseUrl + ", googleApiKey=" + (hasGoogleApiKey() ? "<redacted>" : "<none>")
         + ", features=" + features + ", strategies=" + strategies + ", categories=" + categories + ", maxRunsPerDay=" + maxRunsPerDay
         + ", concurrency=" + concurrency + ", reportRetentionDays=" + reportRetentionDays + ", timeZone=" + timeZone + "}");
   }



   /*******************************************************************************
    ** Whether a value is an absolute http(s) URL with a host.
    *******************************************************************************/
   static boolean isAbsoluteHttpUrl(String value)
   {
      if(value == null)
      {
         return (false);
      }
      try
      {
         URI uri = URI.create(value);
         return (uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())));
      }
      catch(IllegalArgumentException e)
      {
         return (false);
      }
   }



   /*******************************************************************************
    ** Whether a value is a zone id.
    *******************************************************************************/
   private static boolean isZone(String value)
   {
      try
      {
         ZoneId.of(value);
         return (true);
      }
      catch(Exception e)
      {
         return (false);
      }
   }



   /*******************************************************************************
    ** Fluent setter for backendName.
    *******************************************************************************/
   public WebPerfQBitConfig withBackendName(String backendName)
   {
      this.backendName = backendName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for backendName.
    *******************************************************************************/
   public String getBackendName()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** Fluent setter for tableNamePrefix.
    *******************************************************************************/
   public WebPerfQBitConfig withTableNamePrefix(String tableNamePrefix)
   {
      this.tableNamePrefix = tableNamePrefix;
      return (this);
   }



   /*******************************************************************************
    ** Setter for tableNamePrefix.
    *******************************************************************************/
   public void setTableNamePrefix(String tableNamePrefix)
   {
      this.tableNamePrefix = tableNamePrefix;
   }



   /*******************************************************************************
    ** Getter for tableNamePrefix.
    *******************************************************************************/
   public String getTableNamePrefix()
   {
      return (tableNamePrefix);
   }



   /*******************************************************************************
    ** Fluent setter for namePrefix.
    *******************************************************************************/
   public WebPerfQBitConfig withNamePrefix(String namePrefix)
   {
      this.namePrefix = namePrefix;
      return (this);
   }



   /*******************************************************************************
    ** Getter for namePrefix.
    *******************************************************************************/
   public String getNamePrefix()
   {
      return (namePrefix);
   }



   /*******************************************************************************
    ** Fluent setter for appName.
    *******************************************************************************/
   public WebPerfQBitConfig withAppName(String appName)
   {
      this.appName = appName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appName.
    *******************************************************************************/
   public String getAppName()
   {
      return (appName);
   }



   /*******************************************************************************
    ** Fluent setter for appLabel.
    *******************************************************************************/
   public WebPerfQBitConfig withAppLabel(String appLabel)
   {
      this.appLabel = appLabel;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appLabel.
    *******************************************************************************/
   public String getAppLabel()
   {
      return (appLabel);
   }



   /*******************************************************************************
    ** Fluent setter for parentAppName (kof22: the CMS app, D5).
    *******************************************************************************/
   public WebPerfQBitConfig withParentAppName(String parentAppName)
   {
      this.parentAppName = parentAppName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for parentAppName.
    *******************************************************************************/
   public String getParentAppName()
   {
      return (parentAppName);
   }



   /*******************************************************************************
    ** Fluent setter for permissionBaseName.
    *******************************************************************************/
   public WebPerfQBitConfig withPermissionBaseName(String permissionBaseName)
   {
      this.permissionBaseName = permissionBaseName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for permissionBaseName.
    *******************************************************************************/
   public String getPermissionBaseName()
   {
      return (permissionBaseName);
   }



   /*******************************************************************************
    ** Fluent setter for siteBaseUrl (the public origin under test).
    *******************************************************************************/
   public WebPerfQBitConfig withSiteBaseUrl(String siteBaseUrl)
   {
      this.siteBaseUrl = siteBaseUrl;
      return (this);
   }



   /*******************************************************************************
    ** Getter for siteBaseUrl.
    *******************************************************************************/
   public String getSiteBaseUrl()
   {
      return (siteBaseUrl);
   }



   /*******************************************************************************
    ** Fluent setter for googleApiKey (PSI and CrUX; from the environment, never a table).
    *******************************************************************************/
   public WebPerfQBitConfig withGoogleApiKey(String googleApiKey)
   {
      this.googleApiKey = googleApiKey;
      return (this);
   }



   /*******************************************************************************
    ** Getter for googleApiKey.
    *******************************************************************************/
   public String getGoogleApiKey()
   {
      return (googleApiKey);
   }



   /*******************************************************************************
    ** Fluent setter for features.
    *******************************************************************************/
   public WebPerfQBitConfig withFeatures(Set<WebPerfFeature> features)
   {
      this.features = features == null ? null : EnumSet.copyOf(features.isEmpty() ? EnumSet.noneOf(WebPerfFeature.class) : features);
      return (this);
   }



   /*******************************************************************************
    ** Getter for features.
    *******************************************************************************/
   public Set<WebPerfFeature> getFeatures()
   {
      return (features);
   }



   /*******************************************************************************
    ** Fluent setter for strategies.
    *******************************************************************************/
   public WebPerfQBitConfig withStrategies(List<String> strategies)
   {
      this.strategies = strategies == null ? null : new ArrayList<>(strategies);
      return (this);
   }



   /*******************************************************************************
    ** Getter for strategies.
    *******************************************************************************/
   public List<String> getStrategies()
   {
      return (strategies);
   }



   /*******************************************************************************
    ** Fluent setter for categories.
    *******************************************************************************/
   public WebPerfQBitConfig withCategories(List<String> categories)
   {
      this.categories = categories == null ? null : new ArrayList<>(categories);
      return (this);
   }



   /*******************************************************************************
    ** Getter for categories.
    *******************************************************************************/
   public List<String> getCategories()
   {
      return (categories);
   }



   /*******************************************************************************
    ** Fluent setter for contentTables (tables that get the Performance panel).
    *******************************************************************************/
   public WebPerfQBitConfig withContentTables(List<String> contentTables)
   {
      this.contentTables = contentTables == null ? new ArrayList<>() : new ArrayList<>(contentTables);
      return (this);
   }



   /*******************************************************************************
    ** Getter for contentTables.
    *******************************************************************************/
   public List<String> getContentTables()
   {
      return (contentTables);
   }



   /*******************************************************************************
    ** Fluent setter for keyPaths: the pages tested daily and after deploys
    ** (tier KEY when synced), e.g. "/", "/blog", "/projects".
    *******************************************************************************/
   public WebPerfQBitConfig withKeyPaths(List<String> keyPaths)
   {
      this.keyPaths = keyPaths == null ? new ArrayList<>() : new ArrayList<>(keyPaths);
      return (this);
   }



   /*******************************************************************************
    ** Getter for keyPaths.
    *******************************************************************************/
   public List<String> getKeyPaths()
   {
      return (keyPaths);
   }



   /*******************************************************************************
    ** Fluent setter for cruxClient (defaults to Google's with the API key).
    *******************************************************************************/
   public WebPerfQBitConfig withCruxClient(CruxClient cruxClient)
   {
      this.cruxClient = cruxClient;
      return (this);
   }



   /*******************************************************************************
    ** Getter for cruxClient.
    *******************************************************************************/
   public CruxClient getCruxClient()
   {
      return (cruxClient);
   }



   /*******************************************************************************
    ** Fluent setter for concurrency.
    *******************************************************************************/
   public WebPerfQBitConfig withConcurrency(int concurrency)
   {
      this.concurrency = concurrency;
      return (this);
   }



   /*******************************************************************************
    ** Getter for concurrency.
    *******************************************************************************/
   public int getConcurrency()
   {
      return (concurrency);
   }



   /*******************************************************************************
    ** Fluent setter for maxRunsPerDay.
    *******************************************************************************/
   public WebPerfQBitConfig withMaxRunsPerDay(int maxRunsPerDay)
   {
      this.maxRunsPerDay = maxRunsPerDay;
      return (this);
   }



   /*******************************************************************************
    ** Getter for maxRunsPerDay.
    *******************************************************************************/
   public int getMaxRunsPerDay()
   {
      return (maxRunsPerDay);
   }



   /*******************************************************************************
    ** Fluent setter for minSpacingMillis.
    *******************************************************************************/
   public WebPerfQBitConfig withMinSpacingMillis(int minSpacingMillis)
   {
      this.minSpacingMillis = minSpacingMillis;
      return (this);
   }



   /*******************************************************************************
    ** Getter for minSpacingMillis.
    *******************************************************************************/
   public int getMinSpacingMillis()
   {
      return (minSpacingMillis);
   }



   /*******************************************************************************
    ** Fluent setter for cruxSpacingMillis: the pause between CrUX queries
    ** (400 ms keeps them under the API's 150 a minute).
    *******************************************************************************/
   public WebPerfQBitConfig withCruxSpacingMillis(int cruxSpacingMillis)
   {
      this.cruxSpacingMillis = cruxSpacingMillis;
      return (this);
   }



   /*******************************************************************************
    ** Getter for cruxSpacingMillis.
    *******************************************************************************/
   public int getCruxSpacingMillis()
   {
      return (cruxSpacingMillis);
   }



   /*******************************************************************************
    ** Fluent setter for publishSettleSeconds.
    *******************************************************************************/
   public WebPerfQBitConfig withPublishSettleSeconds(int publishSettleSeconds)
   {
      this.publishSettleSeconds = publishSettleSeconds;
      return (this);
   }



   /*******************************************************************************
    ** Getter for publishSettleSeconds.
    *******************************************************************************/
   public int getPublishSettleSeconds()
   {
      return (publishSettleSeconds);
   }



   /*******************************************************************************
    ** Fluent setter for deploySettleSeconds.
    *******************************************************************************/
   public WebPerfQBitConfig withDeploySettleSeconds(int deploySettleSeconds)
   {
      this.deploySettleSeconds = deploySettleSeconds;
      return (this);
   }



   /*******************************************************************************
    ** Getter for deploySettleSeconds.
    *******************************************************************************/
   public int getDeploySettleSeconds()
   {
      return (deploySettleSeconds);
   }



   /*******************************************************************************
    ** Fluent setter for reportRetentionDays (0 keeps reports forever, D8).
    *******************************************************************************/
   public WebPerfQBitConfig withReportRetentionDays(int reportRetentionDays)
   {
      this.reportRetentionDays = reportRetentionDays;
      return (this);
   }



   /*******************************************************************************
    ** Getter for reportRetentionDays.
    *******************************************************************************/
   public int getReportRetentionDays()
   {
      return (reportRetentionDays);
   }



   /*******************************************************************************
    ** Fluent setter for schedulerName.
    *******************************************************************************/
   public WebPerfQBitConfig withSchedulerName(String schedulerName)
   {
      this.schedulerName = schedulerName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for schedulerName.
    *******************************************************************************/
   public String getSchedulerName()
   {
      return (schedulerName);
   }



   /*******************************************************************************
    ** Fluent setter for timeZone.
    *******************************************************************************/
   public WebPerfQBitConfig withTimeZone(String timeZone)
   {
      this.timeZone = timeZone;
      return (this);
   }



   /*******************************************************************************
    ** Getter for timeZone.
    *******************************************************************************/
   public String getTimeZone()
   {
      return (timeZone);
   }



   /*******************************************************************************
    ** Fluent setter for urlSource.
    *******************************************************************************/
   public WebPerfQBitConfig withUrlSource(UrlSource urlSource)
   {
      this.urlSource = urlSource;
      return (this);
   }



   /*******************************************************************************
    ** Getter for urlSource.
    *******************************************************************************/
   public UrlSource getUrlSource()
   {
      return (urlSource);
   }



   /*******************************************************************************
    ** Fluent setter for contentResolver.
    *******************************************************************************/
   public WebPerfQBitConfig withContentResolver(ContentResolver contentResolver)
   {
      this.contentResolver = contentResolver;
      return (this);
   }



   /*******************************************************************************
    ** Getter for contentResolver.
    *******************************************************************************/
   public ContentResolver getContentResolver()
   {
      return (contentResolver);
   }



   /*******************************************************************************
    ** Fluent setter for labRunner.
    *******************************************************************************/
   public WebPerfQBitConfig withLabRunner(LabRunner labRunner)
   {
      this.labRunner = labRunner;
      return (this);
   }



   /*******************************************************************************
    ** Getter for labRunner.
    *******************************************************************************/
   public LabRunner getLabRunner()
   {
      return (labRunner);
   }



   /*******************************************************************************
    ** Fluent setter for rumSource.
    *******************************************************************************/
   public WebPerfQBitConfig withRumSource(RumSource rumSource)
   {
      this.rumSource = rumSource;
      return (this);
   }



   /*******************************************************************************
    ** Getter for rumSource.
    *******************************************************************************/
   public RumSource getRumSource()
   {
      return (rumSource);
   }



   /*******************************************************************************
    ** Fluent adder for a deploy probe.
    *******************************************************************************/
   public WebPerfQBitConfig withDeployProbe(DeployProbe deployProbe)
   {
      this.deployProbes.add(deployProbe);
      return (this);
   }



   /*******************************************************************************
    ** Getter for deployProbes.
    *******************************************************************************/
   public List<DeployProbe> getDeployProbes()
   {
      return (deployProbes);
   }



   /*******************************************************************************
    ** Fluent adder for a change event source.
    *******************************************************************************/
   public WebPerfQBitConfig withChangeEventSource(ChangeEventSource source)
   {
      this.changeEventSources.add(source);
      return (this);
   }



   /*******************************************************************************
    ** Getter for changeEventSources.
    *******************************************************************************/
   public List<ChangeEventSource> getChangeEventSources()
   {
      return (changeEventSources);
   }
}
