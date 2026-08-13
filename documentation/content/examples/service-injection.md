{%
title = "Reports service injection"
description = "A layer-backed route constructor-injects a reports service while selection remains connection-local."
order = 7
section = examples
%}

Select a report loaded through a constructor-injected service. Refresh queries
the service again, while reset changes only connection-local selection state.
The “Open the layer-backed route” link runs the same `ReportsExample.layer`
shown in source with `Reports` supplied by the router environment.

@:example(service-injection)

Related guidance: [inject services with ZLayer](../guides/services-and-zlayer-injection.md#inject-a-service-into-a-liveview).
