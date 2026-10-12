# AGENTS.md

This file provides guidance to Codex when working with code in this repository.

## Project Overview

**QBit Web Performance**: QQQ QBit: PageSpeed-style web performance for QQQ sites (Lighthouse lab runs, CrUX field data, real-user Core Web Vitals, budgets and regressions). Design: [QRun-IO/qqq#1088](https://github.com/QRun-IO/qqq/pull/1088) (`docs/specs/2026-10-12-web-performance-design.md`). Implementation in progress.

## Build Commands

```bash
./mvnw compile          # Compile (always use the wrapper: Maven 3.10 cannot read the parent POM)
./mvnw test             # Unit tests
./mvnw verify           # Unit tests + JaCoCo gate (thresholds in pom.xml)
```

## Architecture

**Entry point:** `WebPerfQBitProducer implements QBitMetaDataProducer<WebPerfQBitConfig>` in `com.kingsrook.qbits.webperf`. Component producers in this package (and below) are found by package scanning.

## Naming (owner rule)

- Tables are lowercase snake_case prefixed with the full qbit name: `web_performance_<name>`. The QQQ table name and the backend (SQL) name are identical; possible-value sources take the table name.
- Permissions use the full qbit name: `web_performance.<action>` (for example `web_performance.view`, `web_performance.admin`).
- Qbit data lives in standard, visible QQQ tables, secured by permissions and record security locks, never hidden.
- Processes run as table or record actions, never in the nav.

## Code Style

- 3-space indentation, opening braces on the next line, `return (x);`.
- Javadoc flower-box comments (`/*****` with `**` lines) on every class and method.
- Fluent setters (`withX()`).
- Wrapper types (`Integer`, `Boolean`) in config and entities.

## Dependencies

- Java 21.
- Parent `com.kingsrook:qbit-build-parent:2.0.0` (qqq 4.0.0). The default-active `qqq-snapshot` profile imports `qqq-bom-pom:4.1.0-RC.4` ahead of it, as in qbit-quick-search (ADR-0007). Remove that activation when the parent moves to 4.1.
- Tests: see pom.xml.

## Release

- CircleCI qqq-orb 0.6.8.
- `develop` publishes SNAPSHOTs.
- A `release/*` branch publishes RCs (the orb bumps the version).
- A `v*` tag publishes the release.
