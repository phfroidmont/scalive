package scalive.render

import java.util.concurrent.atomic.AtomicLong

/** Identity of one compiled node, unique within its render program. */
opaque type TemplateId = Long

object TemplateId:
  private[render] def apply(value: Long): TemplateId = value

  extension (id: TemplateId) def value: Long = id

/** Identity of one dynamic scalar slot in a compiled template. */
opaque type TemplateSlotId = Long

object TemplateSlotId:
  private[render] def apply(value: Long): TemplateSlotId = value

  extension (id: TemplateSlotId) def value: Long = id

/** Compile-time slot from which a lifecycle-scoped binding identity is derived. */
opaque type BindingSlotId = Long

object BindingSlotId:
  private[render] def apply(value: Long): BindingSlotId = value

  extension (id: BindingSlotId) def value: Long = id

/** Monotonic identity of one retained keyed row. Keys select rows; they are never themselves used
  * as protocol identities.
  */
opaque type RowId = Long

object RowId:
  private val nextValue = AtomicLong(0L)

  private[render] def fresh(): Either[RenderError, RowId] =
    val id = nextValue.incrementAndGet()
    if id <= 0L then Left(RenderError.IdentityExhausted("keyed row identity"))
    else Right(id)

  extension (id: RowId) def value: Long = id

/** Unique identity of a render program and its owning lifecycle graph. */
opaque type RenderProgramId = Long

object RenderProgramId:
  private val nextValue = AtomicLong(0L)

  private[render] def fresh(): Either[RenderError, RenderProgramId] =
    val id = nextValue.incrementAndGet()
    if id <= 0L then Left(RenderError.IdentityExhausted("render program identity"))
    else Right(id)

  private[render] def value(id: RenderProgramId): Long = id

/** Identity rendered into event attributes and resolved against committed bindings. */
opaque type BindingId = String

object BindingId:
  private[render] def event(program: RenderProgramId, slot: BindingSlotId): BindingId =
    s"b${RenderProgramId.value(program)}:$slot"

  /** Phoenix requires this value to survive reconnects for a form with the same DOM identity. */
  private[render] def formChange(formId: String): BindingId = s"f${formId.length}:$formId"

  private[render] def js(slot: BindingSlotId): BindingId = s"j${BindingSlotId.value(slot)}"

  // A JS command names each push it contains by its own ID followed by `:js:<index>`.
  private val EventShape      = "b(\\d+):\\d+(?::js:\\d+)?".r
  private val FormChangeShape = "(?s)f(\\d+):(.*)".r
  private val JsShape         = "j\\d+(?::js:\\d+)?".r
  private val JsPushSuffix    = "(?::js:\\d+)?".r

  /** Returns whether `value` has the shape of an ID built above, or of a push inside one, rather
    * than an event name.
    */
  private[scalive] def isGenerated(value: String): Boolean = value match
    case EventShape(_) | JsShape()     => true
    case FormChangeShape(length, rest) =>
      length.toIntOption.exists(formId =>
        formId <= rest.length && JsPushSuffix.matches(rest.drop(formId))
      )
    case _ => false

  /** Returns whether `id` has the shape of a binding `program` renders. Only event bindings name
    * their program, so any other generated shape counts as its own.
    */
  private[scalive] def isFrom(id: BindingId, program: RenderProgramId): Boolean = id match
    case EventShape(owner) => owner.toLongOption.contains(RenderProgramId.value(program))
    case _                 => isGenerated(id)

  def fromEncoded(value: String): BindingId = value

  extension (id: BindingId) def encoded: String = id
end BindingId

/** Monotonic proof of an exact retained render change, never a content hash. */
opaque type RenderRevision = Long

object RenderRevision:
  private[render] val initial: RenderRevision = 0L
  private val nextValue                       = AtomicLong(0L)

  private[render] def next(previous: RenderRevision): Either[RenderError, RenderRevision] =
    val revision = nextValue.incrementAndGet()
    if revision <= 0L || revision <= previous then
      Left(RenderError.IdentityExhausted("render revision"))
    else Right(revision)

  extension (revision: RenderRevision) def value: Long = revision

final private[render] class IdentityAllocator:
  private var nextTemplateValue = 1L
  private var nextSlotValue     = 1L
  private var nextBindingValue  = 1L

  def template(): Either[RenderError, TemplateId] =
    allocate("template identity", nextTemplateValue, TemplateId.apply).map { id =>
      nextTemplateValue += 1L
      id
    }

  def slot(): Either[RenderError, TemplateSlotId] =
    allocate("template slot identity", nextSlotValue, TemplateSlotId.apply).map { id =>
      nextSlotValue += 1L
      id
    }

  def binding(): Either[RenderError, BindingSlotId] =
    allocate("binding slot identity", nextBindingValue, BindingSlotId.apply).map { id =>
      nextBindingValue += 1L
      id
    }

  private def allocate[A](
    kind: String,
    value: Long,
    build: Long => A
  ): Either[RenderError, A] =
    if value <= 0L || value == Long.MaxValue then Left(RenderError.IdentityExhausted(kind))
    else Right(build(value))
end IdentityAllocator
