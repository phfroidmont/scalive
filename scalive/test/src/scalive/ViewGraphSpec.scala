package scalive

import zio.test.*

import scalive.RenderSnapshot.StringSlot

object ViewGraphSpec extends ZIOSpecDefault:

  override def spec = suite("ViewGraphSpec")(
    test("constructs the view once and keeps plain nested HTML static") {
      var constructions = 0
      val graph = ViewGraph.build[Int] { model =>
        constructions += 1
        div(cls := "shell", span("Count: ", model.map(_.toString)))
      }

      val initial = graph.evaluate(1, SignalEvaluation.empty, 1L)
      val next    = graph.evaluate(2, initial.evaluation, 2L)

      assertTrue(
        constructions == 1,
        initial.compiled.root.static == Vector("<div class=\"shell\"><span>Count: ", "</span></div>"),
        initial.compiled.root.slots == Vector(StringSlot("1")),
        next.compiled.root.static.asInstanceOf[AnyRef] eq
          initial.compiled.root.static.asInstanceOf[AnyRef],
        next.compiled.root.slots == Vector(StringSlot("2"))
      )
    },
    test("emits signal attributes as scalar slots") {
      val graph = ViewGraph.build[Boolean] { enabled =>
        button(
          disabled := enabled.map(!_),
          dataAttr("state") := enabled.map(if _ then "enabled" else "disabled"),
          "Save"
        )
      }

      val disabledResult = graph.evaluate(false, SignalEvaluation.empty, 1L)
      val enabledResult  = graph.evaluate(true, disabledResult.evaluation, 2L)
      val diff           = TreeDiff.diff(disabledResult.compiled, enabledResult.compiled)

      assertTrue(
        disabledResult.compiled.root.static == Vector("<button", " data-state=\"", "\">Save</button>"),
        disabledResult.compiled.root.slots == Vector(
          StringSlot(" disabled"),
          StringSlot("disabled")
        ),
        enabledResult.compiled.root.slots == Vector(StringSlot(""), StringSlot("enabled")),
        !diff.isEmpty
      )
    },
    test("stages modifier choices between attributes") {
      val graph = ViewGraph.build[Int] { mode =>
        form(
          mode.chooseMod(
            1 -> (cls      := "validate"),
            2 -> (disabled := true)
          ),
          "Form"
        )
      }

      val changed        = graph.evaluate(1, SignalEvaluation.empty, 1L)
      val disabledResult = graph.evaluate(2, changed.evaluation, 2L)
      val unmatched      = graph.evaluate(3, disabledResult.evaluation, 3L)

      assertTrue(
        RenderSnapshot.renderHtml(changed.compiled) == "<form class=\"validate\">Form</form>",
        RenderSnapshot.renderHtml(disabledResult.compiled) == "<form disabled>Form</form>",
        RenderSnapshot.renderHtml(unmatched.compiled) == "<form>Form</form>"
      )
    },
    test("stages nested wrapper-free modifier choices") {
      val graph = ViewGraph.build[(Boolean, Boolean)] { model =>
        val outer = model.map(_._1)
        val inner = model.map(_._2)
        div(
          outer.chooseMod(
            span("outer"),
            inner.chooseMod(strong("inner true"), em("inner false"))
          )
        )
      }

      val outer = graph.evaluate(true -> false, SignalEvaluation.empty, 1L)
      val innerTrue = graph.evaluate(false -> true, outer.evaluation, 2L)
      val innerFalse = graph.evaluate(false -> false, innerTrue.evaluation, 3L)

      assertTrue(
        RenderSnapshot.renderHtml(outer.compiled) == "<div><span>outer</span></div>",
        RenderSnapshot.renderHtml(innerTrue.compiled) == "<div><strong>inner true</strong></div>",
        RenderSnapshot.renderHtml(innerFalse.compiled) == "<div><em>inner false</em></div>"
      )
    },
    test("suppresses a scalar diff when encoded output is equal") {
      val parity = htmlAttr[Int]("data-parity", codecs.Encoder(value => (value % 2).toString))
      val graph  = ViewGraph.build[Int](model => div(parity := model))

      val initial = graph.evaluate(1, SignalEvaluation.empty, 1L)
      val next    = graph.evaluate(3, initial.evaluation, 2L)

      assertTrue(TreeDiff.diff(initial.compiled, next.compiled).isEmpty)
    },
    test("keeps signal-valued handlers aligned with their compiled snapshot") {
      val graph = ViewGraph.build[Int] { model =>
        button(on.click(model.map(value => s"selected-$value")), "Select")
      }

      val initial = graph.evaluate(1, SignalEvaluation.empty, 1L)
      val next    = graph.evaluate(2, initial.evaluation, 2L)
      val initialHandler = BindingRegistry.collect[String](initial.compiled).values.head
      val nextHandler    = BindingRegistry.collect[String](next.compiled).values.head

      assertTrue(
        initialHandler(Map.empty) == Right("selected-1"),
        nextHandler(Map.empty) == Right("selected-2"),
        initial.compiled.bindings.keySet == next.compiled.bindings.keySet
      )
    }
  )
end ViewGraphSpec
