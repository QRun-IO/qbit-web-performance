# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog; versions follow semantic versioning.

## [0.1.0] - Unreleased

### Changed
- The Performance home is business-only. The provider Setup readout and Recent Runs left the home's widget list (`WebPerfWidgets.HOME`); they are `WebPerfWidgets.ADMIN` (`Setup`, `RecentRuns`), gated to `<base>.admin.hasAccess` and hidden when denied, for a host to place on its Admin area. A new `NotConfigured` widget leads the home: while no Google API key is set it says "Page speed testing is not set up. Ask an admin to add a Google PageSpeed API key." (no config key names), and renders nothing once configured.

### Fixed
- Read the QBit metadata version from the build so release artifacts report their actual version, matching the help bundle.
- Use dropdowns for tier, scope, source, strategy, metric, comparator and severity, preserving existing stored values.
- Allow manual Tested Pages inserts with a URL. Validate absolute HTTPS URLs and fill in the site, path and Manual source automatically; validate URL edits too.

### Added
- Ship the host-neutral `help/qbit-web-performance.yaml` QBIT bundle, register it with QQQ help bundles, and enforce metadata coverage and live-reference validity with `WebPerfHelpBundleTest`. Keep app and table empty-state help available without Help installed.
- Foreign keys for every reference between the QBit's tables (changesets 12-26): findings and reports cascade with their run, comparisons with their site change; optional pointers (likely cause, first and confirmation run, a run's site change) are set to NULL; tested pages and budgets with history cannot be deleted (disable them instead). Rows whose parent is already missing are recorded in `qqq_fk_quarantine`, then nulled or (NOT NULL columns, PostgreSQL) deleted, so the upgrade never fails. Foreign key columns are indexed. A PostgreSQL guardrail test fails when a new reference lacks its constraint.
- Every `web_performance_*` table and the Performance app carry help saying what the table is for and how its rows get created (`help/qbit-web-performance.yaml`, applied by `WebPerfHelp`; host help is never overridden). A Setup card at the top of the home lists tested pages, the lab runner, budgets (reference defaults or your own) and Google field data, each with its state and next step, using the runtime's own resolution. `WebPerfTablesGuardrailTest` fails when a table is unused, unexplained or lacks that help; `WebPerfDefaultsChangelogTest` runs the shipped changelog and its seeded budgets through real Liquibase.
- `reportRetentionDays` is now enforced: with a value above 0, Lighthouse Reports older than that many days are deleted after a result is stored, in batches, at most once an hour per process (runs, scores and findings stay). It was documented but nothing read it; the default 0 still keeps everything.
- Record deploy and external site changes once, queue settled retests, and attribute lab runs to the observed build and CDN stage.
- Evaluate enabled lab and URL CrUX budgets; retain breaches, confirmation evidence, automatic recovery and accurate open counts in the standard regression records.
- Confirm statistical lab regressions with a median of three, reset baselines on Lighthouse major changes, and detect deterministic category/audit failures.
- Add permission-gated Dismiss, Mark resolved and Compare before and after record actions with standard Review/Result summaries.
- Store before/after lab medians, sample counts and windows, including measured TTFB; schedule CDN samples before launch and at one and 24 hours after launch.
- Raise and resolve optional alerts through QQQ core, with logging fallback when no Alerts QBit is installed.

### Changed
- Pin QQQ 4.1.0-RC.7 for the released help-bundle API.
- Use QQQ 4.1.0-RC.6 for the core alerts API; add migration fields for durable evaluation and source-event identity.
- Store complete run results atomically and retain confirmation/comparison identities when retrying samples.

### Previously added
- First implementation; see README.md for the tables, permissions and configuration.
