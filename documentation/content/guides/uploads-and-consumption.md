{%
title = "Uploads and consumption"
description = "Accept bounded files, track immutable upload state, validate content, and transfer resource ownership deliberately."
order = 33
section = guides
%}

## Choose A Destination And Set Hard Limits {#choose-a-destination-and-set-hard-limits}

Start with the resource boundary, not the file input. A
@:apiSymbol(type-alias:scalive.LiveUploadDef)`LiveUploadDef[Result]`@:@ fixes the upload
name, selection policy, limits, and destination result type:

```scala
private val TextFiles = LiveUploadDef.inMemory(
  name = "text-file",
  accept = LiveUploadAccept.only(".txt", ".md"),
  maxEntries = 1,
  maxFileSize = 64L * 1024L
)
```

`inMemory` buffers each file in server heap and returns a `Chunk[Byte]`. Use it
only for small, strictly bounded uploads with modest concurrency. A hosted
@:apiSymbol(type-alias:scalive.LiveUploadWriter)`LiveUploadWriter`@:@ can stream bytes
through application-managed state, while an external destination lets the
browser transfer bytes directly to another service.

The browser reports the name, media type, size, relative path, and progress.
Treat all of those values as untrusted. `LiveUploadAccept` improves selection and
performs early preflight checks; it does not prove the file's type, contents, or
actual destination size.

## Allow The Upload During Every Mount {#allow-the-upload-during-every-mount}

Allow one stable definition and retain the returned
@:apiSymbol(type-alias:scalive.LiveUpload)`LiveUpload[Result]`@:@ snapshot:

```scala
def mount(ctx: MountContext): LiveIO[Model] =
  ctx.uploads.allow(TextFiles).map(Model(_))
```

Disconnected and connected mounts own independent upload registrations. Calling
`allow` in `mount` therefore supports both the initial HTML and the connected
socket without sharing upload state between visitors.

An upload and each `LiveUploadEntry` are immutable point-in-time views. Progress,
cancellation, and consumption return replacement snapshots. Store the returned
value or refresh it through `ctx.uploads.get`; rendering an old value renders old
protocol attributes and status.

## Render Protocol-Owned Controls {#render-protocol-owned-controls}

Use @:apiSymbol(def:scalive.liveFileInput)`liveFileInput`@:@ rather than assembling
the protocol attributes by hand:

```scala
liveFileInput(
  model.upload,
  aria.label := "Text file",
  model.upload.onProgress(_ => Msg.Progress)
)
```

The helper supplies the upload reference, active and completed entry references,
accepted values, and Phoenix upload hook. Render upload-wide and entry-specific
@:apiSymbol(def:scalive.uploadErrors)`uploadErrors`@:@ explicitly. A progress value
is status, not proof that valid content reached a durable destination.

Cancel with the current entry snapshot and retain the returned upload:

```scala
case Msg.Cancel(entry) =>
  ctx.uploads.cancel(entry).map(upload => model.copy(upload = upload))
```

Cancellation releases resources still owned by the upload runtime. A stale or
already removed entry fails with a typed upload operation error.

## Validate Content Before Consuming It {#validate-content-before-consuming-it}

Selection policy is not content validation. Decode, inspect, authorize, or scan
the destination result inside the consume callback before application code takes
ownership. The text example uses a strict UTF-8 decoder and derives aggregate
facts without retaining or rendering the original content.

@:apiSymbol(def:scalive.Uploads.consumeCompleted)`consumeCompleted`@:@ visits valid
completed entries in selection order. It fails while a valid entry is still in
progress, skips invalid entries, and is not transactional: an earlier consumed
entry stays consumed if a later callback fails.

## Make Ownership Explicit {#make-ownership-explicit}

The framework owns a completed destination result while the callback runs. The
returned @:apiSymbol(type-alias:scalive.ConsumeDecision)`ConsumeDecision`@:@ determines
what happens next:

- `Consume(value)` removes the entry and transfers responsibility for its result
  to application code. Destination cleanup is not called.
- `Postpone(value)` keeps the entry and result framework-owned so a later attempt,
  cancellation, disallow, component removal, or socket shutdown can release it.

For an in-memory `Chunk[Byte]`, immediate summarization followed by `Consume`
makes the bytes unreachable after the callback. For a temporary file or external
object, finish validation and persistence or explicitly delete it before
returning `Consume`; after ownership transfer, framework cleanup will not do that
work for you.

## Reset And Release Resources {#reset-and-release-resources}

Disallowing a definition releases every result the runtime still owns. A complete
reset can then allow the stable definition again and return a clean model:

```scala
case Msg.Reset =>
  ctx.uploads.disallow(TextFiles) *>
    ctx.uploads.allow(TextFiles).map(Model(_))
```

Socket shutdown and component removal also clean framework-owned resources on a
best-effort basis. Writers must still account for abrupt process termination and
must release side effects from failed writer operations themselves.

The complete bounded implementation is extracted from executable source:

@:sourceRegion(documentation/site/src/scalive/docs/examples/TextUploadExample.scala, text-upload-example)

Try the [summarize-and-discard text upload](../examples/text-upload.md). Its X-ray
shows lifecycle state and aggregate counts; upload chunks are represented only by
their byte length, never their contents.
