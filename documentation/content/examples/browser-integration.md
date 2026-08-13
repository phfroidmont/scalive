{%
title = "Browser integration"
description = "Composed JS commands and a focused hook exchange correlated, typed browser events."
order = 2
section = examples
%}

Run a composed client-only command, then ask a focused JavaScript hook to copy a
sample string. Clipboard access depends on browser permissions and a secure
context, so denial is handled as an ordinary typed result rather than a crash.

@:example(browser-integration)

Related guidance: [integrate browser behavior](../guides/browser-integration.md#choose-the-boundary)
with commands, directional events, stable hook IDs, correlation, and cleanup.
