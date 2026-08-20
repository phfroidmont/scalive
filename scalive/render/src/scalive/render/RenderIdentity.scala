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

/** Program-namespaced identity rendered into event attributes and resolved against committed
  * bindings.
  */
opaque type BindingId = String

object BindingId:
  private[render] def event(program: RenderProgramId, slot: BindingSlotId): BindingId =
    s"b${RenderProgramId.value(program)}:$slot"

  def fromEncoded(value: String): BindingId = value

  extension (id: BindingId) def encoded: String = id

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
