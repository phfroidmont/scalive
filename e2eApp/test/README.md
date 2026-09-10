# Form Feedback Browser Tests

Run from the repository root inside `nix develop`:

```sh
./scripts/e2e-run-e2e-app.sh
./scripts/e2e-run-e2e-app.sh --repeat-each=3
```

`typed-form-feedback.spec.js` tests the supported API at `/form/typed-feedback`
in Chromium, Firefox, and WebKit. It covers typed controls, scalar blur callbacks,
component targeting, fresh-mount recovery, submission/reset, and keyed rows.

The older `/form/blur-feedback` fixture is an experiment, not a public form API.
Its small raw-value model isolates browser transport from typed form decoding.

## Transport

Ordinary controls execute existing Phoenix JS commands on blur: set a reserved
`phx-value-scalive-blur` form attribute, dispatch `input` on a nameless hidden
control, then remove the attribute. The hidden control has no rate limit and no
input-level change binding, so it synchronously serializes the entire form using
the form-level binding. The event carries both values and the blur marker.

The fixture preserves blurred-field history independently of incoming values.
Numeric debounce can produce an ordinary change followed by the marked blur
event; this mechanism does not promise one total request per blur.

The composite control uses a separate search form and a child LiveComponent.
Its fixture-only hook ignores focus movement inside the widget and clicks a
registered component command on departure. Selection and departure are component
outputs; the parent applies departure to current state rather than resubmitting
potentially stale hidden values. This demonstrates an adapter contract, not a
production searchable selector or a hook required by ordinary controls.

## What The Tests Establish

- Unchanged blur reveals only that field's errors without clearing application status.
- Ordinary edits remain hidden before blur and update feedback live afterward.
- Numeric debounce, throttle, and blur-only debounce deliver current values on blur.
- Static blur-only debounce still delays subsequent edits; it is not a live-feedback mode.
- Markers do not leak into later changes, including another control's pending callback.
- Submit exposes all feedback, later edits preserve visibility, and explicit reset clears it.
- Composite search and internal focus movement do not mark the parent field blurred.
- Composite departure and selection compose through component outputs.

## Normalization Limitation

The phone fixture trims whitespace on blur. After its patch is acknowledged,
later changes preserve the formatted value. However, a later full-form event
serialized before that patch reaches the browser can overwrite normalization.

The race test deliberately sends edit, blur, and another field's edit in one
browser task. Its history proves that normalization happened before a later
snapshot restored the old value. The test characterizes a limitation; its
passing result does not mean normalization is race-safe. This is a consequence
of whole-form snapshot replacement, not evidence of transport reordering or a
Scalive-specific Phoenix-client defect.

## Scope

The raw spike suite runs Chromium against the pinned Phoenix LiveView 1.2.10 client.
Focus/blur ordering and button-click focus behavior are not yet verified in
Firefox or WebKit. Tests do not establish recovery, dynamic row identity,
component removal, IME behavior, or a general stale-value reconciliation policy.
The supported API's separate suite covers typed lifecycle behavior; normalization
remains a characterized limitation rather than a supported convenience operation.
