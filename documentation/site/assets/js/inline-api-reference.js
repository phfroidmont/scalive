const wrapperSelector = ".docs-inline-api-reference"
const triggerSelector = "a[data-api-reference-trigger]"
const previewSelector = "[data-api-reference-preview]"
const hoverDelay = 140
const viewportMargin = 12
const previewGap = 8

export function createInlineApiReferenceEnhancer(root = document) {
  let active
  let pending
  let nextPreviewId = 1

  const elementsFor = (wrapper) => ({
    trigger: wrapper?.querySelector(triggerSelector),
    preview: wrapper?.querySelector(previewSelector),
  })

  const assignPreviewId = (preview) => {
    if (preview.id) return
    let id
    do {
      id = `docs-api-reference-preview-${nextPreviewId++}`
    } while (document.getElementById(id))
    preview.id = id
  }

  const position = () => {
    if (!active) return
    const { trigger, preview } = elementsFor(active)
    if (!trigger || !preview) return

    const anchor = trigger.getBoundingClientRect()
    const bounds = preview.getBoundingClientRect()
    const maxLeft = Math.max(viewportMargin, window.innerWidth - bounds.width - viewportMargin)
    const left = Math.min(
      maxLeft,
      Math.max(viewportMargin, anchor.left + (anchor.width - bounds.width) / 2),
    )
    const below = anchor.bottom + previewGap
    const above = anchor.top - bounds.height - previewGap
    const top = below + bounds.height <= window.innerHeight - viewportMargin
      ? below
      : Math.max(viewportMargin, above)

    preview.style.left = `${Math.round(left)}px`
    preview.style.top = `${Math.round(top)}px`
  }

  const close = (wrapper = active) => {
    if (!wrapper) return
    const { trigger, preview } = elementsFor(wrapper)
    if (preview) {
      preview.hidden = true
      preview.style.removeProperty("left")
      preview.style.removeProperty("top")
    }
    trigger?.removeAttribute("aria-describedby")
    if (active === wrapper) active = undefined
  }

  const open = (wrapper) => {
    const { trigger, preview } = elementsFor(wrapper)
    if (!trigger || !preview) return
    if (active !== wrapper) close()
    active = wrapper
    assignPreviewId(preview)
    trigger.setAttribute("aria-describedby", preview.id)
    preview.hidden = false
    position()
  }

  const cancelPending = () => {
    if (!pending) return
    window.clearTimeout(pending.timeout)
    pending = undefined
  }

  const openAfterDelay = (wrapper) => {
    cancelPending()
    pending = {
      wrapper,
      timeout: window.setTimeout(() => {
        pending = undefined
        open(wrapper)
      }, hoverDelay),
    }
  }

  const wrapperFrom = (target) => target instanceof Element ? target.closest(wrapperSelector) : null
  const supportsHover = () => window.matchMedia("(hover: hover) and (pointer: fine)").matches

  const handlePointerOver = (event) => {
    if (!supportsHover()) return
    const wrapper = wrapperFrom(event.target)
    if (!wrapper || wrapper.contains(event.relatedTarget)) return
    openAfterDelay(wrapper)
  }

  const handlePointerOut = (event) => {
    if (!supportsHover()) return
    const wrapper = wrapperFrom(event.target)
    if (!wrapper || wrapper.contains(event.relatedTarget)) return
    if (pending?.wrapper === wrapper) cancelPending()
    if (!wrapper.contains(document.activeElement)) close(wrapper)
  }

  const handleFocusIn = (event) => {
    const wrapper = wrapperFrom(event.target)
    if (wrapper) {
      cancelPending()
      open(wrapper)
    }
  }

  const handleFocusOut = (event) => {
    const wrapper = wrapperFrom(event.target)
    if (wrapper && !wrapper.contains(event.relatedTarget) && !wrapper.matches(":hover")) {
      close(wrapper)
    }
  }

  const handlePointerDown = (event) => {
    if (active && !active.contains(event.target)) close()
  }

  const handleKeyDown = (event) => {
    if (event.key !== "Escape" || !active) return
    event.preventDefault()
    cancelPending()
    close()
  }

  const enhance = () => {
    root.querySelectorAll(`${wrapperSelector} ${previewSelector}`).forEach(assignPreviewId)
    if (active && !active.isConnected) close()
  }

  root.addEventListener("pointerover", handlePointerOver)
  root.addEventListener("pointerout", handlePointerOut)
  root.addEventListener("focusin", handleFocusIn)
  root.addEventListener("focusout", handleFocusOut)
  root.addEventListener("pointerdown", handlePointerDown)
  root.addEventListener("keydown", handleKeyDown)
  window.addEventListener("resize", position)
  window.addEventListener("scroll", position, true)
  enhance()

  return {
    enhance,
    destroy() {
      cancelPending()
      close()
      root.removeEventListener("pointerover", handlePointerOver)
      root.removeEventListener("pointerout", handlePointerOut)
      root.removeEventListener("focusin", handleFocusIn)
      root.removeEventListener("focusout", handleFocusOut)
      root.removeEventListener("pointerdown", handlePointerDown)
      root.removeEventListener("keydown", handleKeyDown)
      window.removeEventListener("resize", position)
      window.removeEventListener("scroll", position, true)
    },
  }
}
