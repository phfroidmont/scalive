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
    },
    test("renders polymorphic method and extension clauses as Scala syntax") {
      assertTrue(
        ApiSignatureFormatter.format(
          "def appended: [Msg2 >: Msg](mod: Mod[Msg2]*)HtmlElement[Msg2]"
        ) == "def appended[Msg2 >: Msg](mod: Mod[Msg2]*): HtmlElement[Msg2]",
        ApiSignatureFormatter.format(
          "def component: [Props, Msg, Model](liveComponent: LiveComponent[Props, Msg, Model], id: String)LiveComponentInstance[Props, Msg, Model]"
        ) ==
          "def component[Props, Msg, Model](liveComponent: LiveComponent[Props, Msg, Model], id: String): LiveComponentInstance[Props, Msg, Model]",
        ApiSignatureFormatter.format(
          "extension def dropTarget: [R](upload: LiveUpload[R])Mod.Attr[Nothing]"
        ) == "extension def dropTarget[R](upload: LiveUpload[R]): Mod.Attr[Nothing]",
        ApiSignatureFormatter.format(
          "extension def splitBy: [T](items: IterableOnce[T])[Key, Msg](key: T => Key)(project: Function2[Key, T, HtmlElement[Msg]])Mod[Msg]"
        ) ==
          "extension def splitBy[T](items: IterableOnce[T])[Key, Msg](key: T => Key)(project: (Key, T) => HtmlElement[Msg]): Mod[Msg]",
        ApiSignatureFormatter.format("def :=: (value: V)Mod.Attr[Nothing]") ==
          "def :=(value: V): Mod.Attr[Nothing]",
        ApiSignatureFormatter.format(
          "given given_LiveMessageTag_Msg: [Msg](using tag: reflect.ClassTag[Msg])LiveMessageTag[Msg]"
        ) ==
          "given given_LiveMessageTag_Msg[Msg](using tag: reflect.ClassTag[Msg]): LiveMessageTag[Msg]"
      )
    },
    test("renders higher-arity function types as Scala syntax") {
      assertTrue(
        ApiSignatureFormatter.format("def one: Function1[A, R]") == "def one: A => R",
        ApiSignatureFormatter.format("def two: Function2[A, B, R]") ==
          "def two: (A, B) => R",
        ApiSignatureFormatter.format("def five: Function5[A, B, C, D, E, R]") ==
          "def five: (A, B, C, D, E) => R",
        ApiSignatureFormatter.format(
          "def nested: Option[Function2[A, Either[B, C], Function1[D, R]]]"
        ) == "def nested: Option[(A, Either[B, C]) => D => R]"
      )
    },
    test("renders constructor clauses without polymorphic and result types") {
      assertTrue(
        ApiSignatureFormatter.formatConstructor(
          " [Msg](tag: scalive.HtmlTag, mods: scala.collection.immutable.Vector[scalive.Mod[Msg]])scala.Unit",
          Set.empty
        ) ==
          "(tag: scalive.HtmlTag, mods: scala.collection.immutable.Vector[scalive.Mod[Msg]])",
        ApiSignatureFormatter.formatConstructor(
          " (name: String, void: Boolean)scala.Unit",
          Set("void")
        ) == "(name: String, void: Boolean = ...)",
        ApiSignatureFormatter.formatConstructor(
          " ()scala.Unit @uncheckedVariance",
          Set.empty
        ) == "()"
      )
    },
    test("renders defaults inside method parameter clauses") {
      assertTrue(
        ApiSignatureFormatter.format(
          s"def htmlTag: ${ApiSignatureFormatter.markDefaultParameters("(name: String, void: Boolean)HtmlTag", Set("void"))}"
        ) == "def htmlTag(name: String, void: Boolean = ...): HtmlTag",
        ApiSignatureFormatter.format(
          s"def configured: ${ApiSignatureFormatter.markDefaultParameters("[A](first: Option[(A, A)], second: Boolean)(using codec: Codec[A])Result[A]", Set("first", "codec"))}"
        ) ==
          "def configured[A](first: Option[(A, A)] = ..., second: Boolean)(using codec: Codec[A] = ...): Result[A]"
      )
    }
  )
end ApiSignatureFormatterSpec
