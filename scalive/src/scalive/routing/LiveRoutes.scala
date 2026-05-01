package scalive

private[scalive] object LiveRoutes:

  private[scalive] def validateLiveRoutes(
    routes: List[LiveRoute[?, ?, Any, ?, ?, ?]]
  ): Unit =
    val duplicatePaths = routes
      .map(_.pathCodec.render)
      .groupBy(identity)
      .collect { case (path, occurrences) if occurrences.size > 1 => path }
      .toList
      .sorted

    if duplicatePaths.nonEmpty then
      throw new IllegalArgumentException(
        s"Duplicate LiveRoutes paths: ${duplicatePaths.mkString(", ")}"
      )

    val duplicateSessions = routes
      .groupBy(_.sessionName)
      .collect {
        case (name, sessionRoutes) if sessionRoutes.map(_.sessionGroup).distinct.size > 1 => name
      }
      .toList
      .sorted

    if duplicateSessions.nonEmpty then
      throw new IllegalArgumentException(
        s"Duplicate LiveSession names: ${duplicateSessions.mkString(", ")}. " +
          "LiveSession routes must be declared in a single named group."
      )
end LiveRoutes
