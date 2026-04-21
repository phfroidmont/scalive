import ComponentsLiveView.*
import zio.stream.ZStream

import scalive.*

class ComponentsLiveView(initialTab: String) extends LiveView[Msg, Model]:

  def init = Model(activeTab = if initialTab.nonEmpty then initialTab else "focus_wrap")

  def update(model: Model) =
    case Msg.SetTab(tab) => model.copy(activeTab = tab)

  def view(model: Model) =
    div(
      styleAttr := "padding: 1.5rem;",
      h1(
        styleAttr := "font-size: 1.5rem; font-weight: 700; margin-bottom: 1.5rem;",
        "Phoenix Components Demo"
      ),
      div(
        styleAttr := "border-bottom: 1px solid #e5e7eb; margin-bottom: 1.5rem;",
        navTag(
          styleAttr := "margin-bottom: -1px; display: flex; gap: 2rem;",
          a(
            href := "/components?tab=focus_wrap",
            phx.onClick(JS.patch("/components?tab=focus_wrap").push(Msg.SetTab("focus_wrap"))),
            styleAttr :=
              (if model.activeTab == "focus_wrap" then
                 "white-space: nowrap; padding: 0.5rem 0.25rem; border-bottom: 2px solid #3b82f6; color: #2563eb; font-weight: 500; font-size: 0.875rem;"
               else
                 "white-space: nowrap; padding: 0.5rem 0.25rem; border-bottom: 2px solid transparent; color: #6b7280; font-weight: 500; font-size: 0.875rem;"),
            "Focus Wrap"
          )
        )
      ),
      if model.activeTab == "focus_wrap" then focusWrapDemo else ""
    )

  def subscriptions(model: Model) = ZStream.empty

  private def focusWrapDemo =
    div(
      styleAttr := "margin-top: 1.5rem;",
      div(
        h2(
          styleAttr := "font-size: 1.25rem; font-weight: 600; margin-bottom: 1rem;",
          "Phoenix.Component.focus_wrap Demo"
        ),
        p(
          styleAttr := "color: #4b5563; margin-bottom: 1.5rem;",
          "The focus_wrap component wraps tab focus around a container for accessibility. This is essential for modals, dialogs, and menus."
        )
      ),
      div(
        styleAttr := "margin-bottom: 1.5rem;",
        h3(
          styleAttr := "font-size: 1.125rem; font-weight: 500; margin-bottom: 1rem;",
          "Dropdown Menu Example"
        ),
        p(
          styleAttr := "font-size: 0.875rem; color: #4b5563; margin-bottom: 1rem;",
          "Click the button to open a dropdown menu with focus wrapping."
        ),
        div(
          styleAttr := "position: relative; display: inline-block;",
          button(
            idAttr := "dropdown-button",
            phx.onClick(JS.toggle(to = "#dropdown-menu").focusFirst(to = "#dropdown-content")),
            styleAttr := "padding: 0.5rem 1rem; background-color: #4b5563; color: white; border-radius: 0.25rem;",
            "Options ▼"
          ),
          div(
            idAttr := "dropdown-menu",
            styleAttr := "display: none; position: absolute; left: 0; margin-top: 0.5rem; background-color: white; border: 1px solid #d1d5db; border-radius: 0.25rem; box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1); z-index: 10;",
            focusWrap(
              "dropdown-content",
              styleAttr := "padding-top: 0.25rem; padding-bottom: 0.25rem;"
            )(
              menuButton("Edit Profile"),
              menuButton("Settings"),
              menuButton("Sign Out")
            )
          )
        )
      ),
      div(
        h3(
          styleAttr := "font-size: 1.125rem; font-weight: 500; margin-bottom: 1rem;",
          "Simple Focus Container"
        ),
        p(
          styleAttr := "font-size: 0.875rem; color: #4b5563; margin-bottom: 1rem;",
          "A simple container that wraps focus. Notice how Tab navigation cycles within this box."
        ),
        focusWrap(
          "simple-focus-container",
          styleAttr := "border: 2px dashed #d1d5db; padding: 1rem; border-radius: 0.25rem;"
        )(
          div(
            styleAttr := "display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.75rem;",
            button(
              styleAttr := "padding: 0.5rem 0.75rem; background-color: #16a34a; color: white; border-radius: 0.25rem;",
              "Button 1"
            ),
            button(
              styleAttr := "padding: 0.5rem 0.75rem; background-color: #16a34a; color: white; border-radius: 0.25rem;",
              "Button 2"
            )
          ),
          input(
            typ         := "text",
            placeholder := "Input within container",
            styleAttr := "width: 100%; margin-top: 0.75rem; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.25rem;"
          )
        )
      )
    )

  private def menuButton(label: String) =
    button(
      phx.onClick(JS.hide(to = "#dropdown-menu")),
      styleAttr := "display: block; width: 100%; text-align: left; padding: 0.5rem 1rem; font-size: 0.875rem;",
      label
    )
end ComponentsLiveView

object ComponentsLiveView:
  enum Msg:
    case SetTab(tab: String)

  final case class Model(activeTab: String)
