# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog; versions follow semantic versioning.

## [0.1.0] - Unreleased

### Fixed
- Use dropdowns for tier, scope, source, strategy, metric, comparator and severity, preserving existing stored values.
- Allow manual Tested Pages inserts with a URL. Validate absolute HTTPS URLs and fill in the site, path and Manual source automatically; validate URL edits too.

### Added
- Foreign keys for every reference between the QBit's tables (changesets 12-26): findings and reports cascade with their run, comparisons with their site change; optional pointers (likely cause, first and confirmation run, a run's site change) are set to NULL; tested pages and budgets with history cannot be deleted (disable them instead). Rows whose parent is already missing are recorded in `qqq_fk_quarantine`, then nulled or (NOT NULL columns, PostgreSQL) deleted, so the upgrade never fails. Foreign key columns are indexed. A PostgreSQL guardrail test fails when a new reference lacks its constraint.
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
