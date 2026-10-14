package layouts

import quickstart.RootLayout

import scalive.*

object Application:
  // docs:start layouts-application
  def apply(clientAssets: LiveViewClientAssets, assets: StaticAssets): LiveApplication[Any] =
    Live.router
      .withRootLayout(RootLayout(clientAssets, assets))
      .withLayout(AppLayout())(
        Routes.home     -> HomeLiveView(),
        Routes.projects -> ProjectsLiveView(),
        // docs:start layouts-route-layout
        Routes.project.withLayout(ProjectLayout()) -> ProjectLiveView()
        // docs:end layouts-route-layout
      )
  // docs:end layouts-application
