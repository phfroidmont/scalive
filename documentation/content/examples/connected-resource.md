{%
title = "Connected lifecycle registration"
description = "A connected mount acquires one registration handle and releases that exact handle when its LiveView lifecycle closes."
order = 11
section = examples
%}

The connected mount acquires a concrete registration and stores its handle in
the initial model. Updating or resetting ordinary model state does not reacquire
the registration. The preview makes acquisition visible; after its LiveView is
removed there is no remaining UI in which to show cleanup. The extracted source
and its lifecycle tests verify that closing the LiveView releases the exact
displayed handle.

Open the page in another tab to see a distinct handle for that independent
lifecycle. Nested LiveViews follow the same ownership rule even when they share
one WebSocket with their parent. State that should be shared by a logical
application session belongs in a longer-lived service instead.

@:example(connected-resource)

Related guidance: [acquire other connected resources](../guides/async-work-and-subscriptions.md#connected-resources).
