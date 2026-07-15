import zio.http.codec.PathCodec

import scalive.*

object E2ERoutes:
  val keyedComprehension =
    (live / "keyed-comprehension").query[KeyedComprehensionLiveView.UrlParams]

  val navigationA =
    (live / "navigation" / "a").query[NavigationLiveViews.AParams]

  private val navigationBParams =
    (live / "navigation" / "b").queryOptional[String]("container")

  val navigationB =
    navigationBParams.mapParams(container => NavigationLiveViews.BParams(container.contains("1")))(
      params => Option.when(params.withContainerRequested)("1")
    )

  val navigationBItemLocation =
    (live / "navigation" / "b" / PathCodec.string("id"))
      .queryOptional[String]("container")

  val navigationBItemRoute =
    navigationBItemLocation.mapParamsDecodeOnly { case (id, container) =>
      NavigationLiveViews.BParams(container.contains("1"), Some(id))
    }

  val navigationRedirectLoop =
    (live / "navigation" / "redirectloop").query[NavigationLiveViews.RedirectLoopParams]

  val stream     = (live / "stream").queryOptional[String]("empty_item")
  val healthy    = (live / "healthy" / PathCodec.string("category")).params
  val components = (live / "components").query[ComponentsLiveView.UrlParams]
  val portal     = (live / "portal").query[PortalLiveView.QueryParams]

  val formLocation = live / "form"
  val form         = formLocation.paramsDecodeOnly(FormQueryParams.decoder)

  val issue3047A     = live / "issues" / "3047" / "a"
  val issue3047B     = live / "issues" / "3047" / "b"
  val issue3194Other = live / "issues" / "3194" / "other"
  val issue3200      = (live / "issues" / "3200" / PathCodec.string("tab")).params
  val issue3496B     = live / "issues" / "3496" / "b"
  val issue3529      = (live / "issues" / "3529").queryOptional[String]("param")
  val issue3530      = (live / "issues" / "3530").queryOptional[String]("q")
  val issue3612A     = live / "issues" / "3612" / "a"
  val issue3612B     = live / "issues" / "3612" / "b"
  val issue3681      = live / "issues" / "3681"
  val issue3681Away  = live / "issues" / "3681" / "away"
  val issue3686A     = live / "issues" / "3686" / "a"
  val issue3686B     = live / "issues" / "3686" / "b"
  val issue3686C     = live / "issues" / "3686" / "c"
  val issue3709      = live / "issues" / "3709"
  val issue3709Id    = live / "issues" / "3709" / PathCodec.int("id")
  val issue4094      = (live / "issues" / "4094").queryOptional[String]("foo")
end E2ERoutes
