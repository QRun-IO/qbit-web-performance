# qbit-web-performance

PageSpeed-style web performance for any QQQ site. It covers:

- **Lighthouse lab runs**, through the PageSpeed Insights API v5, on mobile and desktop. Each run covers Performance, Accessibility, Best Practices and SEO.
- **Google field data** from CrUX. The QBit records it honestly, as NO_DATA, when Google has none for a page or site.
- **Real-user Core Web Vitals**, read from `qbit-cms-analytics`.
- **Budgets, regressions, site changes and before/after comparisons.**

Everything is stored in standard, visible QQQ tables.

Design and owner decisions: QRun-IO/qqq `docs/specs/2026-10-12-web-performance-design.md` (PR #1088).

## Status

The lab and Google field-data runners, queue, schedules, record actions and performance widgets are implemented. This change adds durable site changes, budget evaluation, confirmed lab regressions and before/after lab comparisons.

- Deploy probes run at startup through the scheduler and every five minutes. New build ids are recorded once per component, and KEY pages are queued after `deploySettleSeconds`. External `ChangeEventSource` events are deduplicated by source registration and their complete event tuple. Keep source registration order stable across restarts. Content events queue enabled pages after `publishSettleSeconds`, because the current SPI does not identify affected target ids.
- Each run records the latest observed frontend build, hostname CDN stage and preceding site change when it starts. A rollback to an already recorded build updates its last observation without duplicating its change row.
- Enabled LAB budgets match site, tier, target, content table or literal path prefix, plus strategy and metric. Comparators describe acceptable values. Breaches are recorded in Regressions with the budget id; there is no separate budget-check table in the design. The strongest matching severity wins. CRUX budgets evaluate URL p75 snapshots; NO_DATA, errors and origin fallback are never treated as page measurements.
- Statistical lab baselines require five successful SCHEDULE, CONFIRM or SITE_AUDIT runs in the preceding 30 days on the same Lighthouse major version. Performance drops of 10 points, LCP increases of both 25% and 500 ms, CLS increases of 0.05, TBT increases of 200 ms, and budget breaches create suspected regressions. Two independent confirmation runs provide a median of three. Category drops of 5 points and newly failing Accessibility, Best Practices or SEO audits confirm immediately. Recovery requires a median of three later runs. Lighthouse major changes record a marker and reset incompatible baselines.
- The page's open count includes suspected and confirmed regressions. **Dismiss** and **Mark resolved** require a note and `web_performance.manage.hasAccess`, use standard Review/Result summary lines, and preserve history.
- **Compare before and after** uses three successful same-version lab samples on each side of a change, with page, sample counts and actual time windows. It stores Performance, LCP, FCP and measured TTFB when available. Results are also updated after attributed runs complete. CDN HEADERS_ONLY queues three samples per strategy; LIVE queues three at +1 hour and three at +24 hours. Insufficient history produces no invented comparison.
- Confirmed regressions raise keyed `webperf.regression` alerts through QQQ core `QAlerts`; recovery, dismissal and manual resolution clear them. `ALERTS` can be disabled. Without an Alerts QBit, core uses its logging sink. No Alerts QBit dependency is required.

Evaluation progress and related writes commit together. Retrying an evaluation does not duplicate incidents, and retrying a failed confirmation preserves its sample reference. The additive changelog must be applied before running the new worker. This version requires QQQ **4.1.0-RC.6** for the core alerts API.

RUM drift and RUM/country before-and-after comparisons remain separate work: the existing `RumSource` exposes fixed daily/28-day aggregates, not arbitrary before/after windows. Enum field metadata and Tested Pages insertion are also outside this change.

## Install

```java
new WebPerfQBitProducer()
   .withQBitConfig(new WebPerfQBitConfig()
      .withBackendName("rdbms")
      .withParentAppName("cms")                                   // D5: a CMS sub-app
      .withSiteBaseUrl("https://www.example.com")
      .withUrlSource(new SitemapUrlSource("https://www.example.com/sitemap.xml"))
      .withDeployProbe(new HttpBuildIdProbe("FRONTEND", "http://frontend:3000/api/build"))
      .withGoogleApiKey(System.getenv("WEBPERF_GOOGLE_API_KEY")))  // optional; lab and CrUX tests are off without it
   .produce(qInstance);
```

The host owns the database. Include the QBit's changelog from the host's master changelog, with the same prefix as the config:

```yaml
- property: { name: web_performance.tablePrefix, value: web_performance_ }
- include: { file: db/liquibase/qbit-web-performance/web_performance.changelog.sql }
```

## Tested pages and dropdowns

Add a manual tested page with an absolute HTTPS URL, an optional label, a tier and an enabled flag. The QBit fills in its site key, path and Manual source. URLs cannot contain credentials or fragments; URL edits use the same validation and update the path. Sitemap sync remains available for automatic discovery.

Tier, Scope, Source, Strategy, Metric, Comparator and Severity use dropdowns with plain labels and the existing stored values. Each table uses its own applicable choices. Existing seeded budgets need no migration.

## Tables

| Table | Written by | Notes |
|---|---|---|
| `web_performance_target` (Tested Pages) | sitemap sync, people | `url`, `label`, `tier` and `enabled` are editable; field-audited |
| `web_performance_lab_run` (Lab Runs) | the QBit | read-only; one Lighthouse run |
| `web_performance_lab_finding` (Findings) | the QBit | read-only; keyed by `(lighthouse_version, audit_id)` |
| `web_performance_lab_report` (Lighthouse Reports) | the QBit | read-only; the gzipped PSI response, kept forever by default |
| `web_performance_field_snapshot` (Google Field Data) | the QBit | read-only; CrUX p75 or NO_DATA |
| `web_performance_budget` (Budgets) | people, seeded | editable; field-audited; 13 seeded reference rows |
| `web_performance_regression` (Regressions) | the QBit, record actions | read-only in the UI; field-audited |
| `web_performance_change_event` (Site Changes) | probes, other QBits | read-only |
| `web_performance_comparison` (Before and After) | the QBit | read-only |

## Permissions

Tables and the app use `READ_WRITE_PERMISSIONS` with the base name `web_performance` (configurable), which gives `web_performance.read` and `web_performance.write`. Processes will add `web_performance.test`, `web_performance.manage` and `web_performance.admin` (design §8.1). No table stores a secret.

## Build

```bash
./mvnw verify
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
