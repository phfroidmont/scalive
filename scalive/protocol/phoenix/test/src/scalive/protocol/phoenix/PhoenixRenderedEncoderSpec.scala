package scalive.protocol.phoenix

import zio.ZIO
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.render.*

object PhoenixRenderedEncoderSpec extends ZIOSpecDefault:
  final case class Model(text: String, raw: String, title: Option[String], disabled: Boolean)

  override def spec = suite("PhoenixRenderedEncoderSpec")(
    test("initial rendered maps reconstruct exactly to HtmlRenderer") {
      val compiled = RenderProgram.compile[Model, Nothing] { model =>
        div(
          cls := "static",
          title.optional(model.map(_.title)),
          disabled := model.map(_.disabled),
          model.map(_.text),
          rawHtml(model.map(_.raw))
        )
      }
      val input = Model("safe < &", "<b>raw</b>", Some("quoted \""), disabled = true)
      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(input)
        encoded   <- ZIO.fromEither(PhoenixRenderedEncoder.initial(candidate.tree))
      yield assertTrue(reconstruct(encoded._2) == HtmlRenderer.render(candidate.tree))
    },
    test("uses stable dense slots and sparse updates without s") {
      val compiled = RenderProgram.compile[Model, Nothing] { model =>
        div(
          title.optional(model.map(_.title)),
          disabled := model.map(_.disabled),
          model.map(_.text),
          rawHtml(model.map(_.raw))
        )
      }
      val firstModel  = Model("one", "<i>raw</i>", None, disabled = false)
      val secondModel = firstModel.copy(text = "two &", title = Some("tip"), disabled = true)
      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(firstModel)
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        second  <- program.evaluate(secondModel, Some(first.commit))
        updated <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, TreeDiffer.diff(first.tree, second.tree))
        )
        empty <- ZIO.fromEither(PhoenixRenderedEncoder.update(updated._1, RenderDelta.Empty))
      yield assertTrue(
        !updated._2.fields.exists(_._1 == "s"),
        updated._2.fields.map(_._1).toSet == Set("0", "1", "2"),
        empty._2 == Json.Obj.empty
      )
    },
    test("a full replacement rebuilds the complete protocol projection") {
      val firstProgram  = RenderProgram.compile[String, Nothing](value => div(value))
      val secondProgram = RenderProgram.compile[String, Nothing](value => div(span(value)))
      for
        first      <- ZIO.fromEither(firstProgram)
        second     <- ZIO.fromEither(secondProgram)
        firstTree  <- first.evaluate("old")
        secondTree <- second.evaluate("new &")
        initial    <- ZIO.fromEither(PhoenixRenderedEncoder.initial(firstTree.tree))
        replaced <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, RenderDelta.Replace(secondTree.tree))
        )
      yield assertTrue(
        replaced._2.fields.exists(_._1 == "s"),
        reconstruct(replaced._2) == HtmlRenderer.render(secondTree.tree)
      )
    },
    test("a structural replacement targets the previous template id and emits a full map") {
      val firstProgram  = RenderProgram.compile[String, Nothing](value => div(span(value)))
      val secondProgram = RenderProgram.compile[String, Nothing](value => div(button(value)))
      for
        first      <- ZIO.fromEither(firstProgram)
        second     <- ZIO.fromEither(secondProgram)
        firstTree  <- first.evaluate("old")
        secondTree <- second.evaluate("new")
        initial    <- ZIO.fromEither(PhoenixRenderedEncoder.initial(firstTree.tree))
        previousId = firstTree.tree.root.children.head.id
        replacement = secondTree.tree.root.children.head
        replaced <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(
            initial._1,
            RenderDelta.Update(
              secondTree.tree.revision,
              Vector(RenderChange.Replace(previousId, replacement))
            )
          )
        )
      yield assertTrue(
        replaced._2.fields.exists(_._1 == "s"),
        reconstruct(replaced._2) == HtmlRenderer.render(secondTree.tree)
      )
    }
  )

  private def reconstruct(rendered: Json.Obj): String =
    val fields = rendered.fields.toMap
    fields("s") match
      case Json.Arr(statics) =>
        statics.zipWithIndex.map { case (static, index) =>
          val value = static match
            case Json.Str(text) => text
            case _              => throw AssertionError("static segment is not a string")
          value + fields
            .get(index.toString).collect { case Json.Str(dynamic) => dynamic }.getOrElse("")
        }.mkString
      case _ => throw AssertionError("rendered map has no static array")
