package scalive

import scala.collection.mutable
import scala.reflect.Enum

/** Bounded Phoenix indexed nested-parameter translation into stable keyed core rows.
  *
  * Use only at a Phoenix compatibility boundary. Phoenix row indexes are positional; this adapter
  * requires `_persistent_id` controls and translates them to core [[FormRowKey]] identity before
  * projection. The original payload and browser target remain available on the resulting event.
  */
final class PhoenixNestedParamsAdapter[Owner, Schema, Domain, Group, Row] private (
  val definition: FormDefinition[Owner, Domain],
  val rows: RepeatedRows[Owner, Group, Row],
  val maxNewRows: Int,
  allocateNew: (Int, Set[String]) => Either[FieldIssue, FormRowKey[Group]],
  blankRow: FormRowKey[Group] => FormRowInitial[Group]):

  require(maxNewRows > 0, "maxNewRows must be positive")

  type Event = FormEvent[Owner, Schema, Domain]
  type Key   = FormRowKey[Group]

  /** Phoenix array-control name used to submit row order and `"new"` placeholders. */
  val sortName: String = PhoenixNestedParamsAdapter.arrayName(rows.path, "_sort")

  /** Phoenix array-control name used to submit row indexes to remove. */
  val dropName: String = PhoenixNestedParamsAdapter.arrayName(rows.path, "_drop")

  /** Translates and projects a Phoenix-compatible payload as a typed event. */
  def event(data: FormData, kind: FormEventKind): Event =
    decode(data, kind, RawFormEvent.Meta.empty)

  /** Translates a payload and positional browser target as a typed event. */
  def event(data: FormData, kind: FormEventKind, target: Option[FormPath]): Event =
    decode(data, kind, RawFormEvent.Meta(target = target))

  /** Handles translated LiveView `phx-change` and recovery with one callback. */
  def onChange[Msg](f: Event => Msg): Mod.Attr[Msg] =
    Mod.Attr.Group(
      Vector(
        on.change.formWith((data, kind, meta) => decode(data, kind, meta))(f),
        on.recover.formWith((data, kind, meta) => decode(data, kind, meta))(f)
      )
    )

  /** Handles translated LiveView `phx-change` and recovery with separate callbacks. */
  def onChange[Msg](changed: Event => Msg, recovered: Event => Msg): Mod.Attr[Msg] =
    Mod.Attr.Group(
      Vector(
        on.change.formWith((data, kind, meta) => decode(data, kind, meta))(changed),
        on.recover.formWith((data, kind, meta) => decode(data, kind, meta))(recovered)
      )
    )

  /** Handles a translated Phoenix-compatible submission. */
  def onSubmit[Msg](f: Event => Msg): Mod.Attr[Msg] =
    on.submit.formWith((data, kind, meta) => decode(data, kind, meta))(f)

  /** Handles a translated submission and decodes one definition-owned submit action. */
  def onSubmit[Action <: Enum, Msg](
    submitter: FormSubmitter[Owner, Schema, Action]
  )(
    f: (Event, Either[FormSubmitter.DecodeError, Action]) => Msg
  ): Mod.Attr[Msg] =
    require(submitter.definition eq definition, "form submitter belongs to another definition")
    on.submit.formWith((data, kind, meta) => decode(data, kind, meta))(event =>
      f(event, submitter.decode(event.data))
    )

  /** Handles a translated Phoenix-compatible recovery payload. */
  def onRecover[Msg](f: Event => Msg): Mod.Attr[Msg] =
    on.recover.formWith((data, kind, meta) => decode(data, kind, meta))(f)

  /** Returns the hidden stable-id control name for a positional Phoenix row index. */
  def persistentIdName(index: Int): String = rowControlName(index, "_persistent_id")

  /** Returns a Phoenix-compatible positional browser name for a declared row field. */
  def fieldName[Input, Value](
    index: Int,
    field: FormField[Group, Input, Value]
  ): String =
    require(rows.typedFields.exists(_ eq field), "field is not declared by this repeated group")
    require(index >= 0, "Phoenix row index must not be negative")
    FormPath
      .fromSegments(
        rows.path.segments ++ Vector(
          FormPathSegment.Name(index.toString)
        ) ++ field.relativePath.segments
      ).name

  /** Phoenix-compatible positional id; use only at the compatibility rendering boundary. */
  def fieldId[Input, Value](
    formId: String,
    index: Int,
    field: FormField[Group, Input, Value]
  ): String =
    require(formId.nonEmpty, "formId must not be empty")
    require(index >= 0, "Phoenix row index must not be negative")
    require(rows.typedFields.exists(_ eq field), "field is not declared by this repeated group")
    val groupNames = FormDefinition
      .names(rows.path).drop(
        FormDefinition.names(rows.group.root.path).length
      )
    ((formId +: groupNames) ++ Vector(index.toString) ++ FormDefinition.names(field.relativePath))
      .mkString("_")

  /** Renders the required mapping from a positional index to a row's stable key. */
  def persistentId[Schema](
    row: FormRowView[Owner, Schema, Group, Row],
    index: Int
  ): HtmlElement[Nothing] =
    input(
      typ      := "hidden",
      nameAttr := persistentIdName(index),
      value    := row.key.value
    )

  /** Renders a Phoenix sort entry for a row at its current positional index. */
  def sortControl[Schema](
    row: FormRowView[Owner, Schema, Group, Row],
    index: Int
  ): HtmlElement[Nothing] =
    input(
      typ                         := "hidden",
      nameAttr                    := sortName,
      value                       := index.toString,
      dataAttr("scalive-row-key") := row.key.value
    )

  private def decode(data: FormData, kind: FormEventKind, meta: RawFormEvent.Meta): Event =
    val translation      = translate(data)
    val translatedTarget = meta.target.flatMap { target =>
      translateTarget(target, translation.indexToKey).orElse(Some(target))
    }
    definition
      .event(
        data,
        kind,
        meta.copy(target = translatedTarget, originalTarget = meta.target),
        translation.data,
        translation.errors
      ).asInstanceOf[Event]

  private[scalive] def translate(data: FormData): PhoenixNestedParamsAdapter.Translation[Owner] =
    val output  = Vector.newBuilder[(String, String)]
    val errors  = mutable.ArrayBuffer.empty[FormError[Owner]]
    val indexed = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[(Vector[String], String)]]
    val indexedValueCounts = mutable.Map.empty[(String, Vector[String]), Int]
    val valueOverflows     = mutable.Set.empty[(String, Vector[String])]
    val sort               = mutable.ArrayBuffer.empty[String]
    val drop               = mutable.ArrayBuffer.empty[String]
    val groupNames         = FormDefinition.names(rows.path)
    val sortPath           = PhoenixNestedParamsAdapter.controlPath(rows.path, "_sort")
    val dropPath           = PhoenixNestedParamsAdapter.controlPath(rows.path, "_drop")
    val parseLimits        = FormPath.ParseLimits(
      definition.limits.maxPathDepth,
      definition.limits.maxSegmentLength
    )
    val recognized = rows.typedFields.map(field => FormDefinition.names(field.relativePath)).toSet
    val maxSortEntries = definition.limits.maxRowsPerGroup.toLong + maxNewRows.toLong
    var sortOverflow   = false
    var dropOverflow   = false
    var indexOverflow  = false

    def addError(message: String, code: String): Unit =
      if errors.size < definition.limits.maxErrors then errors += groupError(message, code)

    def knownLeaf(leaf: Vector[String]): Boolean =
      leaf == Vector("_persistent_id") || recognized.contains(leaf) ||
        leaf.lastOption.exists(_.startsWith(FormDefinition.UnusedPrefix)) && {
          val actual = leaf.init :+ leaf.last.stripPrefix(FormDefinition.UnusedPrefix)
          recognized.contains(actual)
        }

    def acceptIndex(index: String): Boolean =
      if indexed.contains(index) then true
      else if indexed.size < definition.limits.maxRowsPerGroup then
        indexed += index -> mutable.ArrayBuffer.empty
        true
      else
        if !indexOverflow then
          indexOverflow = true
          addError("Too many Phoenix row indexes", "too_many_rows")
        false

    data.raw.foreach { case pair @ (name, value) =>
      FormPath.parse(name, parseLimits) match
        case Left(_)                         => output += pair
        case Right(path) if path == sortPath =>
          if sort.size.toLong < maxSortEntries then sort += value
          else if !sortOverflow then
            sortOverflow = true
            addError("Too many Phoenix sort entries", "too_many_phoenix_sort_entries")
        case Right(path) if path == dropPath =>
          if drop.size <= definition.limits.maxRowsPerGroup then drop += value
          else if !dropOverflow then
            dropOverflow = true
            addError("Too many Phoenix drop entries", "too_many_phoenix_drop_entries")
        case Right(path) =>
          FormDefinition.namesOption(path) match
            case Some(names)
                if names.startsWith(groupNames) && names.length >= groupNames.length + 2 =>
              val remaining = names.drop(groupNames.length)
              val index     = remaining.head
              val leaf      = remaining.tail
              if knownLeaf(leaf) then
                if acceptIndex(index) then
                  val valueKey = index -> leaf
                  val count    = indexedValueCounts.getOrElse(valueKey, 0)
                  val maximum  =
                    if leaf == Vector("_persistent_id") then 2
                    else definition.limits.maxValuesPerField
                  if count < maximum then
                    indexed(index) += leaf -> value
                    indexedValueCounts.update(valueKey, count + 1)
                  else if valueOverflows.add(valueKey) then
                    addError("Too many Phoenix row field values", "too_many_values")
              else output += pair
            case _ => output += pair
    }

    val validIndexes = indexed.keysIterator.filter { index =>
      val valid = PhoenixNestedParamsAdapter.validIndex(index)
      if !valid then addError(s"Invalid Phoenix row index '$index'", "invalid_phoenix_index")
      valid
    }.toVector
    val validIndexSet = validIndexes.toSet
    val dropped       = drop.iterator.filter(_.nonEmpty).toVector
    dropped.iterator.filterNot(validIndexSet).toVector.distinct.foreach { index =>
      addError(s"Unknown Phoenix drop index '$index'", "unknown_phoenix_drop")
    }

    val persistentIds = mutable.LinkedHashMap.empty[String, String]
    validIndexes.foreach { index =>
      val values = indexed(index).collect { case (Vector("_persistent_id"), value) =>
        value
      }.toVector
      values match
        case Vector(value) =>
          FormRowKey.from[Group](value) match
            case Right(_)    => persistentIds += index -> value
            case Left(error) =>
              addError(s"Invalid Phoenix persistent id for index '$index'", error.code)
        case Vector() =>
          addError(s"Missing Phoenix persistent id for index '$index'", "missing_persistent_id")
        case _ =>
          addError(s"Duplicate Phoenix persistent id for index '$index'", "duplicate_persistent_id")
    }

    val persistentIdCounts    = mutable.Map.empty[String, Int]
    val reportedPersistentIds = mutable.Set.empty[String]
    persistentIds.values.foreach { key =>
      val count = persistentIdCounts.getOrElse(key, 0) + 1
      persistentIdCounts.update(key, count)
      if count == 2 && reportedPersistentIds.add(key) then
        addError(s"Duplicate Phoenix persistent id '$key'", "duplicate_persistent_id")
    }

    val ordered     = mutable.ArrayBuffer.empty[Either[Int, String]]
    val seenIndexes = mutable.Set.empty[String]
    var newCount    = 0
    sort.foreach {
      case "new" =>
        if newCount < maxNewRows then
          ordered += Left(newCount)
          newCount += 1
        else if newCount == maxNewRows then
          addError("Too many Phoenix new rows", "too_many_new_rows")
          newCount += 1
      case index if validIndexSet.contains(index) =>
        if seenIndexes.add(index) then ordered += Right(index)
        else addError(s"Duplicate Phoenix sort index '$index'", "duplicate_phoenix_sort")
      case index if index.nonEmpty =>
        addError(s"Unknown Phoenix sort index '$index'", "unknown_phoenix_sort")
      case _ => ()
    }
    validIndexes.foreach { index =>
      if seenIndexes.add(index) then ordered += Right(index)
    }

    val unavailable = persistentIds.values.toSet
    val allocated   = mutable.Set.empty[String]
    val indexToKey  = mutable.Map.empty[String, String]
    ordered.foreach {
      case Left(ordinal) =>
        allocateNew(ordinal, unavailable ++ allocated) match
          case Left(issue) =>
            addError(issue.message, issue.code.getOrElse("new_row_key_allocation"))
          case Right(key) if unavailable.contains(key.value) || !allocated.add(key.value) =>
            addError(
              s"Duplicate allocated Phoenix row key '${key.value}'",
              "duplicate_allocated_key"
            )
          case Right(key) =>
            val initial   = blankRow(key)
            val submitted = initial.assignments.flatMap { assignment =>
              val leaf = FormDefinition.names(assignment.field.relativePath)
              assignment.values.map(leaf -> _)
            }
            appendCoreRow(output, key.value, submitted)
      case Right(index) if !dropped.contains(index) =>
        persistentIds.get(index).foreach { key =>
          if persistentIdCounts(key) == 1 then
            indexToKey += index -> key
            appendCoreRow(output, key, indexed(index).toVector)
        }
      case Right(_) => ()
    }

    PhoenixNestedParamsAdapter.Translation(
      FormData(output.result()),
      errors.toVector,
      indexToKey.toMap
    )
  end translate

  private def appendCoreRow(
    output: mutable.Builder[(String, String), Vector[(String, String)]],
    key: String,
    submitted: Vector[(Vector[String], String)]
  ): Unit =
    val presence = FormPath.fromSegments(
      rows.path.segments ++ Vector(
        FormPathSegment.Name(key),
        FormPathSegment.Name(FormDefinition.RowPresenceName)
      )
    )
    output += presence.name -> FormDefinition.RowPresenceValue
    val recognized = rows.typedFields.map(field => FormDefinition.names(field.relativePath)).toSet
    submitted.foreach { case (leaf, value) =>
      val actual = leaf.lastOption
        .filter(_.startsWith(FormDefinition.UnusedPrefix)).map { marker =>
          leaf.init :+ marker.stripPrefix(FormDefinition.UnusedPrefix)
        }.getOrElse(leaf)
      if recognized.contains(actual) then
        val corePath = FormPath.fromSegments(
          rows.path.segments ++ Vector(FormPathSegment.Name(key)) ++ leaf.map(
            FormPathSegment.Name.apply
          )
        )
        output += corePath.name -> value
    }

  private def translateTarget(
    target: FormPath,
    indexToKey: Map[String, String]
  ): Option[FormPath] =
    FormDefinition.namesOption(target).flatMap { names =>
      val prefix = FormDefinition.names(rows.path)
      if names.startsWith(prefix) && names.length > prefix.length then
        indexToKey.get(names(prefix.length)).map { key =>
          FormPath.fromSegments(
            rows.path.segments ++
              Vector(FormPathSegment.Name(key)) ++
              names.drop(prefix.length + 1).map(FormPathSegment.Name.apply)
          )
        }
      else None
    }

  private def rowControlName(index: Int, control: String): String =
    require(index >= 0, "Phoenix row index must not be negative")
    FormPath
      .fromSegments(
        rows.path.segments ++ Vector(
          FormPathSegment.Name(index.toString),
          FormPathSegment.Name(control)
        )
      ).name

  private def groupError(message: String, code: String): FormError[Owner] =
    FormError(rows.address, FieldIssue(message, Some(code)))
end PhoenixNestedParamsAdapter

/** Creates default or explicitly configured Phoenix compatibility adapters. */
object PhoenixNestedParamsAdapter:
  final private[scalive] case class Translation[Owner](
    data: FormData,
    errors: Vector[FormError[Owner]],
    indexToKey: Map[String, String])

  /** Creates an adapter with deterministic compatibility keys and empty new rows.
    *
    * Use [[configured]] when new rows require initial values or application-defined stable keys.
    */
  def apply[Owner, Domain, Group, Row](
    definition: FormDefinition[Owner, Domain],
    rows: RepeatedRows[Owner, Group, Row],
    maxNewRows: Int = 64
  ): PhoenixNestedParamsAdapter[Owner, definition.type, Domain, Group, Row] =
    configured(definition, rows, maxNewRows)((ordinal, unavailable) =>
      FormRowKey.from[Group](compatibilityKey(ordinal, unavailable)).left.map { error =>
        FieldIssue("Could not allocate a compatibility row key", Some(error.code))
      }
    )(key => rows.row(key)())

  /** Creates an adapter with explicit deterministic key allocation and typed blank-row policy.
    *
    * `allocateNew` receives each `"new"` ordinal and all unavailable keys. `blankRow` must build a
    * row for this adapter's repeated-group schema. Translation and definition limits still apply.
    */
  def configured[Owner, Domain, Group, Row](
    definition: FormDefinition[Owner, Domain],
    rows: RepeatedRows[Owner, Group, Row],
    maxNewRows: Int = 64
  )(
    allocateNew: (Int, Set[String]) => Either[FieldIssue, FormRowKey[Group]]
  )(
    blankRow: FormRowKey[Group] => FormRowInitial[Group]
  ): PhoenixNestedParamsAdapter[Owner, definition.type, Domain, Group, Row] =
    require(definition.owns(rows), "repeated group is not declared by this form definition")
    new PhoenixNestedParamsAdapter(
      definition,
      rows,
      maxNewRows,
      allocateNew,
      blankRow
    )

  private def controlPath(path: FormPath, suffix: String): FormPath =
    val names = FormDefinition.names(path)
    FormPath.fromSegments(
      names.init.map(FormPathSegment.Name.apply) ++ Vector(
        FormPathSegment.Name(names.last + suffix),
        FormPathSegment.Array
      )
    )

  private def arrayName(path: FormPath, suffix: String): String =
    controlPath(path, suffix).name

  private def validIndex(value: String): Boolean =
    value.nonEmpty && value.length <= 16 && value.forall(_.isDigit)

  private def compatibilityKey(ordinal: Int, unavailable: Set[String]): String =
    var candidateOrdinal = ordinal
    var candidate        = s"new_$candidateOrdinal"
    while unavailable.contains(candidate) do
      candidateOrdinal += 1
      candidate = s"new_$candidateOrdinal"
    candidate
end PhoenixNestedParamsAdapter
