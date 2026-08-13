{%
title = "Summarize-and-discard text upload"
description = "One bounded text file becomes aggregate facts while its consumed bytes are immediately discarded."
order = 110
section = examples
%}

Choose one small `.txt` or `.md` file and submit it after the upload completes.
The LiveView validates strict UTF-8 text, retains only byte, line, and word counts,
and immediately releases the consumed bytes. It does not persist or render the
file contents.

The limits make an in-memory destination appropriate for this lesson. Larger or
attacker-controlled files need a streaming hosted writer or a direct external
destination instead.

@:example(text-upload)

Related guidance: [accept, consume, and release uploads safely](../guides/uploads-and-consumption.md#choose-a-destination-and-set-hard-limits).
