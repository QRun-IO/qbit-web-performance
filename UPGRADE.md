# Upgrading

## Business-only home; admin widgets (unreleased)

No DDL change. The Setup and Recent Runs widgets are no longer on the Performance home. They are still registered (`webPerformanceSetup`, `webPerformanceRecentRuns`), gated to `web_performance.admin.hasAccess` and hidden without it; add `WebPerfWidgets.ADMIN` (qualified with `config.qualify`) to your Admin app's widgets to keep them. The home gains a `webPerformanceNotConfigured` widget that shows only while no Google API key is set.

## Table help, Setup card and `reportRetentionDays` (unreleased)

No DDL change, no new changeset and no host include change. The qbit now fills empty `help` slots on its app and tables (help a host set is kept) and adds a Setup widget to the Performance home. `reportRetentionDays` (default 0, keep forever) is now enforced: set it above 0 only if you want Lighthouse Reports older than that many days deleted.
