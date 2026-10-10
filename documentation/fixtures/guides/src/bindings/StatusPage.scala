package bindings

import zio.IO

import scalive.*

object StatusPage:
  // docs:start bindings-static-html
  val rendered: IO[StaticHtmlError, String] = StaticHtml.render(
    htmlRootTag(
      lang := "en",
      headTag(titleTag("Status")),
      bodyTag(mainTag(h1("Ready")))
    ),
    includeDoctype = true
  )
  // docs:end bindings-static-html
