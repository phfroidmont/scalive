package scalive

/** Selects the client DOM patching mode emitted by `phx.update`.
  *
  * These values correspond to Phoenix LiveView's `phx-update` protocol. Prefer higher-level Scalive
  * helpers where they own required structure; in particular, `LiveStream.renderIn` supplies stream
  * container and row IDs automatically.
  */
enum PhxUpdate(private[scalive] val value: String):
  /** Applies normal LiveView patching, replacing content with the latest server-rendered tree. */
  case Replace extends PhxUpdate("replace")

  /** Treats the element as a stream container whose children are patched by stable DOM ID. */
  case Stream extends PhxUpdate("stream")

  /** Preserves client-managed element content while still allowing `data-*` attribute updates. */
  case Ignore extends PhxUpdate("ignore")
