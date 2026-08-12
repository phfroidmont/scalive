{%
title = "Lifecycle and connection state"
description = "Connection-aware mounting, keyed flash messages, page-title projection, and after-render effects."
order = 3
section = examples
%}

Compare the connected mount with declarative LiveSocket state, put and clear one
keyed notification, and change the title projected from the model. The example
runs as an isolated nested @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@, so it displays its projected title without
claiming ownership of the documentation page's browser title.

@:example(lifecycle)

Related guidance: [follow the complete lifecycle](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts)
or [apply flash, title, and connection UX](../guides/flash-title-and-lifecycle-ux.md#render-keyed-flash).
