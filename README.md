# qbit-web-performance

PageSpeed-style web performance for any QQQ site. It covers:

- **Lighthouse lab runs**, through the PageSpeed Insights API v5, on mobile and desktop. Each run covers Performance, Accessibility, Best Practices and SEO.
- **Google field data** from CrUX. The QBit records it honestly, as NO_DATA, when Google has none for a page or site.
- **Real-user Core Web Vitals**, read from `qbit-cms-analytics`.
- **Budgets, regressions, site changes and before/after comparisons.**

Everything is stored in standard, visible QQQ tables.

Design and owner decisions: QRun-IO/qqq `docs/specs/2026-10-12-web-performance-design.md` (PR #1088).

## Status

This is a **skeleton (0.1.0-SNAPSHOT)**. It is local only, and the GitHub repository does not exist yet. It contains:

- the producer and config, with validation and secret redaction
- the nine `web_performance_` tables, their lookups and the Performance app
- the host-included changelog, including the seeded budgets (D12)
- the SPIs: `UrlSource`, `ContentResolver`, `LabRunner`, `DeployProbe`, `RumSource`, `ChangeEventSource`
- two implementations that need no Google API key: `SitemapUrlSource` and `HttpBuildIdProbe`, which reads Website-Frontend's `/api/build`

Not built yet: the PSI and CrUX runners (they need the key), the queue worker, the schedules, the actions, the widgets, and the Alerts qbit producer integration.

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

## Tables

| Table | Written by | Notes |
|---|---|---|
| `web_performance_target` (Tested Pages) | sitemap sync, people | `label`, `tier` and `enabled` are editable; field-audited |
| `web_performance_lab_run` (Lab Runs) | the QBit | read-only; one Lighthouse run |
| `web_performance_lab_finding` (Findings) | the QBit | read-only; keyed by `(lighthouse_version, audit_id)` |
| `web_performance_lab_report` (Lighthouse Reports) | the QBit | read-only; the gzipped PSI response, kept forever by default |
| `web_performance_field_snapshot` (Google Field Data) | the QBit | read-only; CrUX p75 or NO_DATA |
| `web_performance_budget` (Budgets) | people, seeded | editable; field-audited; 13 seeded reference rows |
| `web_performance_regression` (Regressions) | the QBit, record actions | read-only in the UI; field-audited |
| `web_performance_change_event` (Site Changes) | probes, other QBits | read-only |
| `web_performance_comparison` (Before and After) | the QBit | read-only |

## Permissions

Tables and the app use `READ_WRITE_PERMISSIONS` with the base name `webPerformance` (configurable), which gives `webPerformance.read` and `webPerformance.write`. Processes will add `webPerformance.test`, `webPerformance.manage` and `webPerformance.admin` (design §8.1). No table stores a secret.

## Build

```bash
./mvnw verify
```

License: Apache-2.0.
