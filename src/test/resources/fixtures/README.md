# Test fixtures

- `psi-kof22-home-mobile.json`: a real Lighthouse 13.5.0 result for https://www.kof22.com/ (mobile, all four categories), run with the Lighthouse CLI on 2026-10-12 and wrapped in the PageSpeed Insights v5 response envelope (`kind`, `id`, `lighthouseResult`, `analysisUTCTimestamp`). Screenshots and i18n strings were removed to keep it small. There is no `loadingExperience`, because Google has no CrUX data for the site. Replace it with a response captured from the PSI API once the key is live.
- `crux-origin-phone.json`: a CrUX API `queryRecord` response with the documented shape (p75 per metric, CLS as a string, histograms, collection period). Its values are illustrative and used only in tests.
