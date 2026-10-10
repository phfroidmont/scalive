{%
title = "Summarize-and-discard text upload"
description = "One bounded text file becomes aggregate facts while its consumed bytes are immediately discarded."
order = 9
section = examples
%}

A form that uploads one small text file, counts its bytes, lines, and words,
and immediately discards the file. It never stores or renders the file's
contents.

## Try it {#try-it}

1. Under **Text file**, choose a `.txt` or `.md` file of at most 64 KiB. The
   file appears at 0%: choosing a file does not upload it yet.
2. Click **Upload and summarize**. The browser uploads the file, then the
   server summarizes it: a card shows its size, lines, and words, and the
   status reads "Summary created. The uploaded bytes were discarded."
3. Choose a file of another type, or one larger than 64 KiB. An error such as
   "Choose a .txt or .md file." appears, and the file cannot be uploaded.
4. Click **Reset upload** to clear the file and every summary.

@:example(text-upload)

## How it works {#how-it-works}

- `LiveUploadDef.inMemory` allows one `.txt` or `.md` file of at most 64 KiB.
  The server checks a chosen file's type and size before the browser sends any
  bytes.
- The upload is manual, as by default: submitting the form uploads the file and
  then sends the submit message.
- `ctx.uploads.consumeCompleted` hands the completed bytes to the summary
  function once, then releases them. Invalid UTF-8 is rejected with a message
  instead of a summary.

These limits make an in-memory destination appropriate for this example.
Larger or attacker-controlled files need a streaming writer or an external
destination instead.

## Related guides {#related-guides}

- [File uploads](../guides/uploads-and-consumption.md#choose-a-destination-and-set-hard-limits)
  covers destinations, limits, and consumption.
