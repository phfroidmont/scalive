package scalive.docs.pipeline

import zio.test.*

object ApiSignatureFormatterSpec extends ZIOSpecDefault:
  override def spec = suite("ApiSignatureFormatterSpec")(
    test("removes the scalive root qualifier from displayed types") {
      assertTrue(
        ApiSignatureFormatter.format("def hooks: scalive.LiveHooks[Msg, Model]") ==
          "def hooks: LiveHooks[Msg, Model]",
        ApiSignatureFormatter.format(
          "trait Eventless[Model, Params] extends scalive.LiveView.Eventless[Model] with scalive.LiveView.Routed[scala.Nothing, Model, Params]"
        ) ==
          "trait Eventless[Model, Params] extends LiveView.Eventless[Model] with LiveView.Routed[Nothing, Model, Params]",
        ApiSignatureFormatter.format("def upload: scalive.package.LiveUpload[R]") ==
          "def upload: LiveUpload[R]",
        ApiSignatureFormatter.format("def encoder: scalive.codecs.Encoder[A, String]") ==
          "def encoder: codecs.Encoder[A, String]",
        ApiSignatureFormatter.format("def upload: scalive.upload.LiveUpload[R]") ==
          "def upload: upload.LiveUpload[R]"
      )
    },
    test("preserves package declarations and external qualifiers") {
      assertTrue(
        ApiSignatureFormatter.format("package scalive") == "package scalive",
        ApiSignatureFormatter.format("package scalive.codecs") == "package scalive.codecs",
        ApiSignatureFormatter.format("def effect: zio.ZIO[R, E, A]") ==
          "def effect: zio.ZIO[R, E, A]"
      )
    }
  )
end ApiSignatureFormatterSpec
