(function () {
  "use strict";

  var installationKey = "__scaliveNavigationGuardV1";
  var existing = window[installationKey];
  if (existing) {
    existing.synchronize();
    return;
  }

  var marker = "data-scalive-navigation-guard";
  var selector = "[data-scalive-navigation-guard]";
  var suppressionTimeout = 10000;
  var beforeUnloadInstalled = false;
  var suppressBeforeUnload = false;
  var acceptedNavigation = null;
  var restoreTimer = null;

  function activeGuard() {
    return document.querySelector(selector);
  }

  function beforeUnload(event) {
    var guard = activeGuard();
    if (!guard || suppressBeforeUnload) return;

    var message = guard.getAttribute(marker);
    event.preventDefault();
    event.returnValue = "";
  }

  function synchronize() {
    var shouldInstall = !!activeGuard() && !suppressBeforeUnload;
    if (shouldInstall && !beforeUnloadInstalled) {
      window.addEventListener("beforeunload", beforeUnload);
      beforeUnloadInstalled = true;
    } else if (!shouldInstall && beforeUnloadInstalled) {
      window.removeEventListener("beforeunload", beforeUnload);
      beforeUnloadInstalled = false;
    }
  }

  function restoreGuarding(token) {
    if (token && (!acceptedNavigation || acceptedNavigation.token !== token)) return;

    if (restoreTimer) window.clearTimeout(restoreTimer);
    restoreTimer = null;
    acceptedNavigation = null;
    if (suppressBeforeUnload) suppressBeforeUnload = false;
    synchronize();
  }

  function suppressFor(event) {
    var token = {};
    var detail = event.detail || {};
    acceptedNavigation = { token: token, href: detail.href };
    suppressBeforeUnload = true;
    synchronize();

    if (restoreTimer) window.clearTimeout(restoreTimer);
    restoreTimer = window.setTimeout(function () {
      restoreGuarding(token);
    }, suppressionTimeout);

    return token;
  }

  function navigationCompleted(event) {
    if (!acceptedNavigation) return;

    var detail = event.detail || {};
    if (!acceptedNavigation.href || detail.href === acceptedNavigation.href) {
      restoreGuarding(acceptedNavigation.token);
    }
  }

  function beforeNavigate(event) {
    synchronize();
    var guard = activeGuard();
    if (!guard) return;

    var message = guard.getAttribute(marker);
    if (!window.confirm(message)) {
      event.preventDefault();
      return;
    }

    if (event.detail && event.detail.pop) return;

    // A LiveView navigation may fall back to a hard navigation. Keep beforeunload
    // detached until the in-page navigation commits or the bounded fallback expires.
    var token = suppressFor(event);

    // Another listener can still reject this navigation after the user accepted it.
    Promise.resolve().then(function () {
      if (event.defaultPrevented) restoreGuarding(token);
    });
  }

  window[installationKey] = { synchronize: synchronize };
  window.addEventListener("phx:before-navigate", beforeNavigate);
  window.addEventListener("phx:navigate", navigationCompleted);
  document.addEventListener("phx:update", synchronize);
  window.addEventListener("pageshow", function () {
    restoreGuarding();
  });
  synchronize();
})();
