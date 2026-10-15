(function () {
  "use strict";

  if (window.liveSocket) {
    console.warn(
      "Scalive did not connect: window.liveSocket already exists. " +
        "Pass LiveClient.Custom to ZioHttp.routes when the application connects its own LiveSocket."
    );
    return;
  }

  var socketPath = document.currentScript.getAttribute("data-socket-path");
  var options = window.liveSocketOptions || {};
  var csrfMeta = document.querySelector("meta[name='csrf-token']");
  var csrf = csrfMeta ? { _csrf_token: csrfMeta.getAttribute("content") } : {};
  var params = options.params;
  var withCsrf =
    typeof params === "function"
      ? function () {
          return Object.assign({}, params.apply(this, arguments), csrf);
        }
      : Object.assign({}, params, csrf);

  var liveSocket = new LiveView.LiveSocket(
    socketPath,
    Phoenix.Socket,
    Object.assign({}, options, { params: withCsrf })
  );
  liveSocket.connect();
  window.liveSocket = liveSocket;
})();
