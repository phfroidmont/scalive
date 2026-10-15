package scalive

/** How pages served by [[ZioHttp.routes]] get the Phoenix LiveView browser client.
  *
  * Scalive always serves its navigation-guard runtime and adds it to every page. See [client
  * setup](https://scalive.dev/guides/static-assets-and-client-setup).
  */
enum LiveClient:
  /** Scalive serves the Phoenix 1.8.9 and Phoenix LiveView 1.2.10 clients it packages, adds them to
    * every page, and connects a `LiveSocket` after the page's own scripts have run.
    *
    * The connection uses the router's socket path and the page's CSRF token. A page script that
    * sets `window.liveSocketOptions` adds `LiveSocket` options such as `hooks`, `uploaders`, or
    * `params`.
    */
  case Packaged

  /** The application's own browser bundle includes the Phoenix clients and connects its
    * `LiveSocket`.
    */
  case Custom
