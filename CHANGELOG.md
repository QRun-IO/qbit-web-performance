# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog; versions follow semantic versioning.

## [0.1.0] - Unreleased

### Added
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
