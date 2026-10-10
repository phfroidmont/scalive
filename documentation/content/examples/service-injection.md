{%
title = "Reports service injection"
description = "A route builds its LiveView from a reports service while selection remains connection-local."
order = 10
section = examples
%}

A LiveView that loads its reports from a service it receives in its
constructor. The reports come from the service; which report is selected is
connection-local state.

## Try It {#try-it}

1. Click **Open incidents**. **Selected report** shows "Open incidents" and "3
   incidents need an owner."
2. Click **Refresh reports**. The LiveView asks the service again and selects
   the first report.
3. Click **Open service-backed route** to open the same LiveView on its own
   route. This leaves the current page; use the browser's Back button to return.

@:example(service-injection)

## How It Works {#how-it-works}

- `ReportsExample` takes a `Reports` service as a constructor parameter and
  calls `reports.recent` in `mount` and on refresh.
- `ReportsExample.route` registers the route with
  `.from((reports: Reports) => ReportsExample(reports))`. The route adds
  `Reports` to the application's requirements, and the server provides it with
  `Reports.inMemory`.
- A service failure becomes the `Failed` model, which renders "Reports are
  temporarily unavailable." instead of crashing the page.

## Related Guides {#related-guides}

- [Services and dependency injection](../guides/services-and-zlayer-injection.md#inject-a-service-into-a-liveview)
  explains `from` and service layers.
- [Loading data and live updates](../learn/loading-data-and-live-updates.md#give-the-page-its-service)
  builds a service-backed page step by step.
