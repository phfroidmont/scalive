{%
title = "Reports service injection"
description = "A route builds its LiveView from a reports service while selection remains connection-local."
order = 7
section = examples
%}

Select a report loaded through a constructor-injected service. Refresh queries
the service again, while reset changes only connection-local selection state.
The **Open service-backed route** link opens `ReportsExample.route`, shown in the
source below. That route builds the LiveView with `.from`, taking `Reports` from
the application environment.

@:example(service-injection)

Related guidance: [inject services into LiveViews](../guides/services-and-zlayer-injection.md#inject-a-service-into-a-liveview).
