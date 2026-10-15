package layouts

import scalive.*

object Application:
  // docs:start layouts-application
  def apply(assets: StaticAssets): LiveApplication[Any] =
    Live.router
      .withRootLayout(RootLayout(assets))
      .withLayout(AppLayout())(
        Routes.home     -> HomeLiveView(),
        Routes.projects -> ProjectsLiveView(),
        // docs:start layouts-route-layout
        Routes.project.withLayout(ProjectLayout()) -> ProjectLiveView()
        // docs:end layouts-route-layout
      )
  // docs:end layouts-application
