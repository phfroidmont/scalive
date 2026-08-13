{%
title = "Typed documentation navigation"
description = "Named route builders encode real documentation search parameters into checked LiveLocation values."
order = 8
section = examples
%}

Choose a search preset and inspect the encoded destination before navigating.
Both controls receive the same @:apiSymbol(class:scalive.LiveLocation)`LiveLocation`@:@ from the
documentation site's named search route. Push navigation adds a browser-history
entry; replace navigation replaces the current entry.

@:example(navigation)

Related guidance: [declare typed routes and choose navigation semantics](../guides/routes-and-navigation.md#build-locations-from-route-declarations).
