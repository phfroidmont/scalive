{%
title = "Voting components"
description = "Stable component instances isolate local votes while typed outputs report changes to their parent LiveView."
order = 6
section = examples
%}

Vote in either card to update only that component's local model. Its typed output
is mapped into a parent message, so the parent can identify the reporting
instance without relying on a runtime component ID. Then update the Scala props:
the title changes while its vote count remains intact.

@:example(voting-components)

Related guidance: [build stateful components and communicate with their owner](../guides/components-and-communication.md#map-component-outputs).
