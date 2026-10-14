# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog; versions follow semantic versioning.

## [0.1.0] - Unreleased

### Fixed
- Use dropdowns for tier, scope, source, strategy, metric, comparator and severity, preserving existing stored values.
- Allow manual Tested Pages inserts with a URL. Validate absolute HTTPS URLs and fill in the site, path and Manual source automatically; validate URL edits too.

### Added
- Every `web_performance_*` table and the Performance app carry help saying what the table is for and how its rows get created (`help/web-performance.yaml`, applied by `WebPerfHelp`; host help is never overridden). A Setup card at the top of the home lists tested pages, the lab runner, budgets (reference defaults or your own) and Google field data, each with its state and next step, using the runtime's own resolution. `WebPerfTablesGuardrailTest` fails when a table is unused, unexplained or lacks that help; `WebPerfDefaultsChangelogTest` runs the shipped changelog and its seeded budgets through real Liquibase.
- `reportRetentionDays` is now enforced: with a value above 0, Lighthouse Reports older than that many days are deleted after a result is stored, in batches, at most once an hour per process (runs, scores and findings stay). It was documented but nothing read it; the default 0 still keeps everything.
- Record deploy and external site changes once, queue settled retests, and attribute lab runs to the observed build and CDN stage.
- Evaluate enabled lab and URL CrUX budgets; retain breaches, confirmation evidence, automatic recovery and accurate open counts in the standard regression records.
- Confirm statistical lab regressions with a median of three, reset baselines on Lighthouse major changes, and detect deterministic category/audit failures.
- Add permission-gated Dismiss, Mark resolved and Compare before and after record actions with standard Review/Result summaries.
- Store before/after lab medians, sample counts and windows, including measured TTFB; schedule CDN samples before launch and at one and 24 hours after launch.
- Raise and resolve optional alerts through QQQ core, with logging fallback when no Alerts QBit is installed.

### Changed
- Use QQQ 4.1.0-RC.6 for the core alerts API; add migration fields for durable evaluation and source-event identity.
- Store complete run results atomically and retain confirmation/comparison identities when retrying samples.

### Previously added
- First implementation; see README.md for the tables, permissions and configuration.
