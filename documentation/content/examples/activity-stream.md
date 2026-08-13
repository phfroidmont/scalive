{%
title = "Bounded activity stream"
description = "Durable activity history drives an opaque stream handle with stable IDs and a five-row DOM window."
order = 5
section = examples
%}

Insert activities until the rendered list retains only its five newest rows.
The durable count keeps growing because the model owns the complete history;
the @:apiSymbol(type-alias:scalive.LiveStream)`LiveStream`@:@ only describes efficient DOM updates.
Delete removes an activity from both, while reset restores the initial history
and stream window.

@:example(activity-stream)

Related guidance: [use streams for collection updates](../guides/streams-and-collection-updates.md#separate-domain-state-from-stream-state).
