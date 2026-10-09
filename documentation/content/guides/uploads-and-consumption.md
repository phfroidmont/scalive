{%
title = "File uploads"
description = "Accept bounded files, choose a hosted or external destination, report progress, and transfer resource ownership deliberately."
order = 13
section = guides
group = "Interfaces and input"
%}

## Before You Start {#prerequisites}

Start with a LiveView that can retain immutable values in its model and handle
form change, submit, and upload progress messages. Every upload mode needs the
working browser connection from
[Client setup and static assets](static-assets-and-client-setup.md). An external
upload additionally needs the custom uploader registration shown below.

## Complete A Bounded In-Memory Upload {#complete-a-bounded-in-memory-upload}

Use this small-file path first:

1. define strict acceptance, count, and byte limits;
2. call `allow` during every mount and retain its upload snapshot;
3. render `liveFileInput` and progress feedback;
4. refresh the upload snapshot when progress arrives; and
5. consume completed entries, then retain the replacement snapshot.

A @:apiSymbol(type-alias:scalive.LiveUploadDef)`LiveUploadDef[Result]`@:@ fixes the upload
name, selection policy, limits, destination result type, and whether transfer
starts automatically:

@:sourceRegion(documentation/fixtures/guides/src/uploads/TextUploadLiveView.scala, uploads-definition)

`inMemory` buffers every byte in server heap and returns `Chunk[Byte]`, so keep
the limits strict and expected concurrency modest.

## Allow The Upload During Every Mount {#allow-the-upload-during-every-mount}

Keep one stable definition value and retain the returned
@:apiSymbol(type-alias:scalive.LiveUpload)`LiveUpload[Result]`@:@ snapshot:

@:sourceRegion(documentation/fixtures/guides/src/uploads/TextUploadLiveView.scala, uploads-mount)

Disconnected and connected mounts own independent registrations. Calling
`allow` in `mount` supports both the initial HTML and the connected LiveView
without sharing state between visitors.

An upload and each `LiveUploadEntry` are immutable point-in-time views.
Progress, cancellation, and consumption return replacement snapshots. Store
the returned value or refresh it through `ctx.uploads.get`; rendering an old
value renders old protocol attributes and status.

## Render Selection, Drop, And Progress Controls {#render-selection-drop-and-progress-controls}

Use @:apiSymbol(def:scalive.liveFileInput)`liveFileInput`@:@ rather than assembling
protocol attributes by hand. Inside `view`, `model` is a `Signal[Model]`, so
select the upload with `model.map(_.upload)` and pass that signal to the upload
helpers. Put `upload.dropTarget` on any element that should accept files dropped
for this upload:

@:sourceRegion(documentation/fixtures/guides/src/uploads/TextUploadLiveView.scala, uploads-render)

The file input supplies the upload reference, active, preflighted, and completed
entry references, accepted values, multiplicity, automatic-upload marker, and
Phoenix upload hook. A drop target only routes dropped files to that input; it
does not relax acceptance, count, or size checks.

`upload.onProgress(Msg.Progress)` is a DOM event binding. Handle the message by
fetching the latest snapshot rather than trusting raw browser metadata:

@:sourceRegion(documentation/fixtures/guides/src/uploads/TextUploadLiveView.scala, uploads-progress)

The overload taking `Map[String, String] => Msg` exposes raw `ref`,
`entry_ref`, `progress`, and optional `error` values. They are client-controlled
protocol data, not authorization evidence.

Render upload-wide and entry-specific
@:apiSymbol(def:scalive.uploadErrors)`uploadErrors`@:@ explicitly, as
`errorMessages` does above. Display `entry.progress` as status only; 100 percent
is not proof that valid content reached permanent storage.

This definition keeps the default `autoUpload = false`, so selecting a file only
validates it. Submitting the form starts the transfer, and Phoenix sends the
submit event after the transfer completes. Automatic transfer is covered after
the basic path.

## Consume And Transfer Ownership {#consume-and-transfer-ownership}

@:apiSymbol(def:scalive.Uploads.consumeCompleted)`consumeCompleted`@:@ visits valid
completed entries in selection order. It fails while a valid entry is still in
progress, skips invalid entries, and is not transactional: an earlier consumed
entry stays consumed if a later callback fails.

The framework owns a completed destination result while the callback runs. The
returned @:apiSymbol(type-alias:scalive.ConsumeDecision)`ConsumeDecision`@:@ determines
what happens next:

- `Consume(value)` removes the entry and transfers responsibility for its result to application code. Destination cleanup is not called.
- `Postpone(value)` keeps the entry and result framework-owned so a later attempt, cancellation, disallow, component removal, or socket shutdown can release it.

For this bounded in-memory path, process the completed bytes before returning
`Consume`, then retain both the callback values and replacement upload snapshot:

@:sourceRegion(documentation/fixtures/guides/src/uploads/TextUploadLiveView.scala, uploads-consume)

Retain the returned replacement upload snapshot even when application code does
not need the callback results. Cancel with the current entry snapshot and retain
that returned upload too:

@:sourceRegion(documentation/fixtures/guides/src/uploads/TextUploadLiveView.scala, uploads-cancel)

A stale or already removed entry fails with a typed upload operation error.

## Compare Hosted And External Destinations {#choose-a-destination-and-set-hard-limits}

After the bounded path works, choose the factory by where bytes should travel:

- `inMemory` buffers every byte in server heap and returns `Chunk[Byte]`. Use it only for small, strictly bounded uploads with modest concurrency.
- `hosted` sends browser chunks through Scalive to a `LiveUploadWriter[State, Result]`. Use it to stream into a temporary file, scanner, object-store SDK, or another application-managed sink without collecting the whole file in heap.
- `external` asks a `LiveUploadExternalUploader[Result]` for browser-visible configuration, then lets the browser send bytes directly to another service. Scalive retains only the server-side `Result` handle.

The browser reports the name, media type, size, relative path, modification
time, and progress. Treat all of them as untrusted. `LiveUploadAccept` improves
selection and performs early preflight checks; it does not prove the file's
type, contents, destination size, or integrity.

`maxEntriesMode = LiveUploadMaxEntriesMode.Selected` is the default: consuming or
cancelling an entry frees capacity for a later selection. Use
`LiveUploadMaxEntriesMode.Total` when `maxEntries` is a lifecycle-wide cap and
consumed entries must continue to count until the upload is allowed again. Pass
`validator = Some(metadata => ...)` to reject browser-reported metadata during
preflight with an application-defined reason. The validator is an early usability
check, not authorization or content validation.

Destination ownership changes what must happen before the consume callback
returns. Persist, verify, or atomically move a hosted result before returning
`Consume`. Verify and finalize an external reserved object first. Return
`Postpone` after a retryable application failure so the runtime still owns and
can release the destination result.

## Choose Manual Or Automatic Transfer {#choose-manual-or-automatic-transfer}

The default `autoUpload = false` waits for the form submit before valid entries
start transferring. Set `autoUpload = true` when selection should start transfer
immediately:

@:sourceRegion(documentation/fixtures/guides/src/uploads/HostedUploads.scala, uploads-auto-upload)

`tempFileWriter` is the hosted writer shown in
[Stream through a hosted writer](#stream-through-a-hosted-writer).

Automatic transfer does not consume an entry, publish a result, or submit the
surrounding form. Keep a separate Save action that validates application state
and calls `consume` or `consumeCompleted` after entries complete.

For a server-side progress hook, pass `progress = Some(...)` on the definition:

@:sourceRegion(documentation/fixtures/guides/src/uploads/HostedUploads.scala, uploads-progress-hook)

This callback runs after an accepted browser progress report has updated runtime
state. It is not called for every hosted chunk, and its effect failing fails the
progress operation without rolling the state update back. Use it for lightweight
observation or orchestration, not exact byte accounting. The writer is the
authoritative place to count hosted bytes.

## Stream Through A Hosted Writer {#stream-through-a-hosted-writer}

A hosted writer owns an in-progress `State` and eventually produces a typed
`Result`. A temporary-file writer can follow this shape:

@:sourceRegion(documentation/fixtures/guides/src/uploads/HostedUploads.scala, uploads-hosted-writer)

Use a server-generated path. Never resolve `client.fileName` or
`client.relativePath` directly into storage. Enforce a destination-side byte
limit and inspect or scan content in `complete` or before consumption. Chunk
boundaries are transport details and are not content boundaries.

The runtime threads only successfully returned state. `abort` releases an
initialized state after cancellation, disallow, component removal, socket
shutdown, or upload failure. `discard` releases a completed result still owned
by the runtime. If a failed `init`, `writeChunk`, or `complete` call creates a
side effect not represented by the last successful state, that call must clean
it itself. Framework cleanup is best-effort and cannot run after process death,
so also sweep abandoned temporary resources by age.

Fail a writer method with `LiveUploadWriterError(reason)` when the browser should
receive a stable, non-sensitive reason. Other failures are reported as the generic
`writer_error`; exception messages and stack traces are never exposed through the
upload protocol.

## Upload Directly To An External Service {#upload-directly-to-an-external-service}

An external uploader authorizes one entry, reserves a server-side handle, and
returns only browser-safe configuration:

@:sourceRegion(documentation/fixtures/guides/src/uploads/ExternalUploads.scala, uploads-external-uploader)

Authorize in `preflight`, generate the object key on the server, scope signed
credentials narrowly, and keep server secrets out of `ExternalUploadClientConfig`.
Return `LiveExternalUploadResult.Error(meta)` for a safe structured rejection.
If preparation fails or rejects after reserving a resource but before returning
`Ready`, release that resource in `preflight`; Scalive has no result to pass to
`discard` yet.

The `uploader` string must match a Phoenix external uploader installed when the
browser creates `LiveSocket`. For a signed PUT workflow:

```javascript
const uploaders = {
  "object-store": (entries, onViewError) => {
    entries.forEach(entry => {
      const xhr = new XMLHttpRequest()
      onViewError(() => xhr.abort())
      xhr.upload.addEventListener("progress", event => {
        if (event.lengthComputable) {
          entry.progress(Math.min(99, Math.round((event.loaded / event.total) * 100)))
        }
      })
      xhr.addEventListener("load", () => {
        if (xhr.status >= 200 && xhr.status < 300) entry.progress(100)
        else entry.error()
      })
      xhr.addEventListener("error", () => entry.error())
      xhr.open(entry.meta.method, entry.meta.url, true)
      xhr.send(entry.file)
    })
  },
}

const liveSocket = new LiveView.LiveSocket("/live", Phoenix.Socket, { params, uploaders })
```

The Phoenix client treats progress `100` as a finished upload. The `progress`
listener therefore stops at `99`, and only the `load` handler reports `100`,
after it has checked the response status. Otherwise a request that the storage
service rejects could already count as complete.

Scalive never receives external bytes. Browser-reported completion merely makes
the prepared result consumable. In the consume callback, query the external
service and verify ownership, final size, media type, checksum or integrity, and
scan status as appropriate before publishing the object and returning `Consume`.

## Reset And Release Resources {#reset-and-release-resources}

Disallowing a definition releases every result the runtime still owns. A full
reset can then allow the stable definition again:

@:sourceRegion(documentation/fixtures/guides/src/uploads/TextUploadLiveView.scala, uploads-reset)

Socket shutdown and component removal also clean framework-owned resources on a
best-effort basis. Once `Consume` transfers ownership, application retention,
deletion, and failure compensation policies apply instead.

The complete bounded in-memory recipe from this page:

@:sourceRegion(documentation/fixtures/guides/src/uploads/TextUploadLiveView.scala, uploads-liveview)

Try the [summarize-and-discard text upload](../examples/text-upload.md). Its
diagnostic view shows lifecycle state and aggregate counts; upload chunks are
represented only by their byte length, never their contents.

## Related Tasks {#related-tasks}

- Use [Typed forms and validation](typed-forms-and-validation.md) for metadata submitted with an upload.
- Use [Asynchronous work, subscriptions, and connected resources](async-work-and-subscriptions.md) when scanning or post-processing should continue as lifecycle-owned work.
- Use [Testing](testing.md) to exercise selection limits, cancellation, cleanup, retries, and invalid content.
- Use [Troubleshooting](troubleshooting.md) when the browser hook, socket path, or static assets prevent transfer.
