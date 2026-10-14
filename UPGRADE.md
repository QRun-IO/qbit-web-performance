# Upgrading

## Table help, Setup card and `reportRetentionDays` (unreleased)

No DDL change, no new changeset and no host include change. The qbit now fills empty `help` slots on its app and tables (help a host set is kept) and adds a Setup widget to the Performance home. `reportRetentionDays` (default 0, keep forever) is now enforced: set it above 0 only if you want Lighthouse Reports older than that many days deleted.
