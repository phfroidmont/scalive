package scalive

import scala.collection.mutable
import scala.deriving.Mirror

/** Resource bounds applied while projecting untrusted payloads into semantic values.
  *
  * These limits bound accepted structure and accumulated errors; the original [[FormEvent.data]]
  * remains available for diagnostics.
  */
final case class FormLimits(
  maxPathDepth: Int = 32,
  maxSegmentLength: Int = 256,
  maxValuesPerField: Int = 128,
  maxRowsPerGroup: Int = 256,
  maxErrors: Int = 256):
  require(maxPathDepth > 0, "maxPathDepth must be positive")
  require(maxSegmentLength > 0, "maxSegmentLength must be positive")
  require(maxValuesPerField > 0, "maxValuesPerField must be positive")
  require(maxRowsPerGroup > 0, "maxRowsPerGroup must be positive")
  require(maxErrors > 0, "maxErrors must be positive")

final private[scalive] case class CanonicalFormRow(
  key: String,
  fields: Map[Vector[String], Vector[String]])

final private[scalive] case class CanonicalFormGroup(rows: Vector[CanonicalFormRow])

/** Canonical, metadata-free editable values belonging to one exact form definition.
  *
  * The `Schema` parameter is the definition's path-dependent singleton type. Values cannot be
  * safely reused with another definition, even one declaring identical paths.
  */
final class FormValues[Owner, Schema] private[scalive] (
  private[scalive] val schemaIdentity: AnyRef,
  private[scalive] val static: Map[FormAddress[Owner], Vector[String]],
  private[scalive] val groups: Map[FormAddress[Owner], CanonicalFormGroup]):

  override def equals(other: Any): Boolean = other match
    case that: FormValues[?, ?] =>
      (schemaIdentity eq that.schemaIdentity) && static == that.static && groups == that.groups
    case _ => false

  override def hashCode(): Int =
    System.identityHashCode(schemaIdentity) * 31 * 31 + static.hashCode() * 31 + groups.hashCode()

  override def toString: String = "FormValues(<redacted>)"

final private[scalive] case class FormProjection[Owner, Schema](
  values: FormValues[Owner, Schema],
  structuralErrors: Vector[FormError[Owner]],
  used: Set[FormAddress[Owner]])

/** One coherent schema responsible for projection, validation, events, and rebuilding.
  *
  * Start with [[FormRoot.product]], then use [[initial]] for server values or [[event]] for an
  * untrusted browser payload. The path-dependent aliases keep forms, events, snapshots, and
  * workflows tied to this exact definition instance.
  */
final class FormDefinition[Owner, Domain] private[scalive] (
  val root: FormPath,
  private[scalive] val owner: FormRoot,
  private[scalive] val parts: Vector[FormPart[?, ?]],
  private val construct: Vector[Any] => Either[FormErrors[Owner], Domain],
  val limits: FormLimits):
  self =>

  /** Singleton identity used to prevent mixing artifacts from distinct definitions. */
  type Schema = self.type

  /** Editable values owned by this exact definition. */
  type Values = FormValues[Owner, self.type]

  /** Current form owned by this exact definition. */
  type Form = scalive.Form[Owner, self.type, Domain]

  /** Typed event rebuilt by this exact definition. */
  type Event = FormEvent[Owner, self.type, Domain]

  /** Save workflow whose current and baseline values share this definition. */
  type Workflow[Failure] = FormWorkflow[Owner, self.type, Domain, Failure]

  /** Valid value snapshot owned by this exact definition. */
  type Snapshot = ValidFormSnapshot[Owner, self.type, Domain]

  private val rootAddress = FormAddress.fromPath[Owner](root)

  /** Logical root address used for product-level errors. */
  val address: FormAddress[Owner]                          = rootAddress
  private val staticFields: Vector[FormField[Owner, ?, ?]] = parts.collect {
    case field: FormField[?, ?, ?] => field.asInstanceOf[FormField[Owner, ?, ?]]
  }
  private val rowGroups: Vector[RepeatedRows[Owner, ?, ?]] = parts.collect {
    case rows: RepeatedRows[?, ?, ?] => rows.asInstanceOf[RepeatedRows[Owner, ?, ?]]
  }
  private val staticByPath: Map[Vector[String], FormField[Owner, ?, ?]] =
    staticFields.map(field => FormDefinition.names(field.path) -> field).toMap

  FormDefinition.validateSchema(owner, parts)

  /** Creates a pristine form from typed or explicitly raw schema-owned assignments.
    *
    * Assignments must originate from fields and repeated groups declared by this definition.
    */
  def initial(assignments: FormInitial[Owner]*): Form =
    val (static, groups) = initialValues(assignments.toVector)
    rebuild(new FormValues(this, static, groups), FormInteraction.pristine, Vector.empty)

  /** Projects an untrusted browser payload and returns a consistent typed event.
    *
    * Projection is bounded by [[limits]]. The event retains the original payload while its
    * [[FormEvent.form]] contains only canonical schema-owned values and structural errors.
    */
  def event(data: FormData, kind: FormEventKind): Event =
    event(data, kind, RawFormEvent.Meta.empty)

  /** Revalidates this definition's values with pristine interaction. */
  def fromValues(values: Values): Form =
    rebuild(values, FormInteraction.pristine, Vector.empty)

  /** Revalidates values while preserving an existing interaction state. */
  def fromValues(values: Values, interaction: FormInteraction[Owner]): Form =
    rebuild(values, interaction, Vector.empty)

  /** Handles LiveView `phx-change` and recovery as typed definition-owned events. */
  def onChange[Msg](f: Event => Msg): Mod.Attr[Msg] =
    Mod.Attr.Group(
      Vector(
        on.change.form(this)(f),
        on.recover.form(this)(f)
      )
    )

  /** Handles LiveView `phx-change` and recovery with separate typed callbacks. */
  def onChange[Msg](
    changed: Event => Msg,
    recovered: Event => Msg
  ): Mod.Attr[Msg] =
    Mod.Attr.Group(
      Vector(
        on.change.form(this)(changed),
        on.recover.form(this)(recovered)
      )
    )

  /** Handles submit events; submitted forms expose all validation errors. */
  def onSubmit[Msg](f: Event => Msg): Mod.Attr[Msg] =
    on.submit.form(this)(f)

  /** Handles LiveView recovery as a typed event. */
  def onRecover[Msg](f: Event => Msg): Mod.Attr[Msg] =
    on.recover.form(this)(f)

  /** Applies dependent whole-product refinement using a fresh definition identity. */
  def map[B](f: Domain => B): FormDefinition[Owner, B] =
    new FormDefinition(root, owner, parts, values => construct(values).map(f), limits)

  /** Applies error-producing whole-product refinement using a fresh definition identity. */
  def emap[B](
    f: Domain => Either[FormErrors[Owner], B]
  ): FormDefinition[Owner, B] =
    new FormDefinition(root, owner, parts, values => construct(values).flatMap(f), limits)

  /** Returns an equivalent definition with new projection limits and a fresh schema identity. */
  def withLimits(value: FormLimits): FormDefinition[Owner, Domain] =
    new FormDefinition(root, owner, parts, construct, value)

  /** Creates a product-level error at this definition's root address. */
  def errors(issue: FieldIssue): FormErrors[Owner] =
    FormErrors.one(rootAddress, issue)

  /** Creates an error for a static field declared by this definition. */
  def errors[Input, Value](
    field: FormField[Owner, Input, Value],
    issue: FieldIssue
  ): FormErrors[Owner] =
    require(owns(field), "error field is not declared by this definition")
    FormErrors.one(field.address, issue)

  /** Creates an error for a repeated group declared by this definition. */
  def errors[Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    issue: FieldIssue
  ): FormErrors[Owner] =
    require(owns(rows), "error group is not declared by this definition")
    FormErrors.one(rows.address, issue)

  /** Starts a [[FormWorkflow]] whose baseline is the supplied form's current values. */
  def workflow[Failure](form: Form): Workflow[Failure] =
    FormWorkflow.create(this, form)

  private[scalive] def owns(field: FormField[Owner, ?, ?]): Boolean =
    staticFields.exists(_ eq field)

  private[scalive] def owns[Group, Row](rows: RepeatedRows[Owner, Group, Row]): Boolean =
    rowGroups.exists(_ eq rows)

  private[scalive] def rebuild(
    values: Values,
    interaction: FormInteraction[Owner],
    structuralErrors: Vector[FormError[Owner]]
  ): Form =
    val decoded = decode(values, structuralErrors)
    new scalive.Form(this, values, decoded, interaction)

  private[scalive] def event(
    data: FormData,
    kind: FormEventKind,
    meta: RawFormEvent.Meta
  ): Event = event(data, kind, meta, data, Vector.empty)

  private[scalive] def event(
    data: FormData,
    kind: FormEventKind,
    meta: RawFormEvent.Meta,
    translatedData: FormData,
    extraErrors: Vector[FormError[Owner]]
  ): Event =
    val projection = project(translatedData)
    val visibility = kind match
      case FormEventKind.Submitted => ErrorVisibility.All
      case _                       => ErrorVisibility.UsedOnly
    val interaction = FormInteraction(projection.used, visibility)
    val form        = rebuild(
      projection.values,
      interaction,
      projection.structuralErrors ++ extraErrors
    )
    val target    = meta.target.flatMap(path => addressForBrowserPath(path, projection.values))
    val eventMeta = FormEventMeta(
      target = target,
      submitter = meta.submitter,
      metadata = meta.metadata,
      browserTarget = meta.originalTarget.orElse(meta.target),
      diagnostics = meta.diagnostics
    )
    new FormEvent(form, data, kind, eventMeta)

  private[scalive] def project(data: FormData): FormProjection[Owner, self.type] =
    val staticValues = mutable.LinkedHashMap.empty[FormAddress[Owner], mutable.ArrayBuffer[String]]
    val staticOverflow = mutable.Set.empty[FormAddress[Owner]]
    val groupData      = rowGroups.map(rows => rows.address -> FormDefinition.GroupPayload()).toMap
    val structural     = mutable.ArrayBuffer.empty[FormError[Owner]]
    val ordinaryUsed   = mutable.Set.empty[FormAddress[Owner]]
    val explicitlyUnused = mutable.Set.empty[FormAddress[Owner]]
    val parseLimits      = FormPath.ParseLimits(limits.maxPathDepth, limits.maxSegmentLength)

    def addStructural(error: FormError[Owner]): Unit =
      if structural.size < limits.maxErrors then structural += error

    def acceptKey(
      rows: RepeatedRows[Owner, ?, ?],
      payload: FormDefinition.GroupPayload,
      key: String
    ): Boolean =
      if payload.discoveredKeys.contains(key) then true
      else if payload.discoveredKeys.size < limits.maxRowsPerGroup then
        payload.discoveredKeys += key
        true
      else
        if !payload.rowsOverflow then
          payload.rowsOverflow = true
          addStructural(
            FormError(
              rows.address,
              FieldIssue("Too many repeated rows submitted", Some("too_many_rows"))
            )
          )
        false

    def incompatiblePath(path: FormPath): Unit =
      staticFields.find(field => path.startsWith(field.path)).foreach { field =>
        addStructural(
          FormError(
            field.address,
            FieldIssue("Invalid scalar field path", Some("invalid_field_path"))
          )
        )
      }
      rowGroups.find(rows => path.startsWith(rows.path)).foreach { rows =>
        addStructural(
          FormError(
            rows.address,
            FieldIssue("Invalid repeated-row path", Some("invalid_row_path"))
          )
        )
      }

    data.raw.foreach { case (browserName, value) =>
      FormPath.parse(browserName, parseLimits) match
        case Left(error) =>
          addStructural(
            FormError(
              rootAddress,
              FieldIssue(s"Malformed form field name '$browserName'", Some(error.code))
            )
          )
        case Right(path) =>
          FormDefinition.namesOption(path) match
            case None        => incompatiblePath(path)
            case Some(names) =>
              staticByPath.get(names) match
                case Some(field) =>
                  val buffer =
                    staticValues.getOrElseUpdate(field.address, mutable.ArrayBuffer.empty)
                  if buffer.size < limits.maxValuesPerField then buffer += value
                  else if staticOverflow.add(field.address) then
                    addStructural(
                      FormError(
                        field.address,
                        FieldIssue("Too many values submitted", Some("too_many_values"))
                      )
                    )
                  ordinaryUsed += field.address
                case None =>
                  staticUnusedAddress(names).foreach(explicitlyUnused += _)
                  staticFields.find(field =>
                    names.startsWith(FormDefinition.names(field.path))
                  ) match
                    case Some(field) =>
                      addStructural(
                        FormError(
                          field.address,
                          FieldIssue("Invalid scalar field path", Some("invalid_field_path"))
                        )
                      )
                    case None =>
                      rowGroups
                        .find(rows => names.startsWith(FormDefinition.names(rows.path))).foreach {
                          rows =>
                            val groupNames = FormDefinition.names(rows.path)
                            val remaining  = names.drop(groupNames.length)
                            if remaining.length < 2 then
                              addStructural(
                                FormError(
                                  rows.address,
                                  FieldIssue("Invalid repeated-row path", Some("invalid_row_path"))
                                )
                              )
                            else
                              val key     = remaining.head
                              val leaf    = remaining.tail
                              val payload = groupData(rows.address)
                              if leaf == Vector(FormDefinition.RowPresenceName) then
                                if acceptKey(rows, payload, key) then
                                  if !payload.presenceCounts.contains(key) then
                                    payload.presence += key -> value
                                  payload.presenceCounts.update(
                                    key,
                                    math.min(2, payload.presenceCounts.getOrElse(key, 0) + 1)
                                  )
                                  if value != FormDefinition.RowPresenceValue then
                                    payload.invalidPresence += key
                              else
                                rows.typedFields.find { field =>
                                  FormDefinition.names(field.relativePath) == leaf
                                } match
                                  case Some(field) if acceptKey(rows, payload, key) =>
                                    val valueKey = key -> leaf
                                    val buffer   = payload.leaves.getOrElseUpdate(
                                      valueKey,
                                      mutable.ArrayBuffer.empty
                                    )
                                    if buffer.size < limits.maxValuesPerField then buffer += value
                                    else if payload.valueOverflow.add(valueKey) then
                                      addStructural(
                                        FormError(
                                          rows.address,
                                          FieldIssue(
                                            "Too many row field values submitted",
                                            Some("too_many_values")
                                          )
                                        )
                                      )
                                    payload.recognizedKeys += key
                                    ordinaryUsed += rowFieldAddress(rows, key, field)
                                  case Some(_) => ()
                                  case None    =>
                                    rowUnusedAddress(rows, key, leaf).foreach { address =>
                                      if acceptKey(rows, payload, key) then
                                        payload.recognizedKeys += key
                                        explicitlyUnused += address
                                    }
                              end if
                            end if
                        }
                  end match
    }

    val canonicalGroups = rowGroups.map { rows =>
      val payload       = groupData(rows.address)
      val canonicalRows = Vector.newBuilder[CanonicalFormRow]

      payload.presence.foreach { case (key, marker) =>
        FormRowKey.from[Any](key) match
          case Left(error) =>
            addStructural(
              FormError(rows.address, FieldIssue(s"Invalid row key '$key'", Some(error.code)))
            )
          case Right(_) =>
            if payload.invalidPresence.contains(key) then
              addStructural(
                FormError(
                  rows.address,
                  FieldIssue("Invalid row-presence marker", Some("invalid_row_presence"))
                )
              )
            if payload.presenceCounts(key) > 1 then
              addStructural(
                FormError(
                  rows.address,
                  FieldIssue(s"Duplicate row-presence marker for '$key'", Some("duplicate_row"))
                )
              )
            if marker == FormDefinition.RowPresenceValue &&
              !payload.invalidPresence.contains(key) && payload.presenceCounts(key) == 1
            then
              val fields = rows.typedFields.flatMap { field =>
                val relative = FormDefinition.names(field.relativePath)
                payload.leaves.get(key -> relative).map(values => relative -> values.toVector)
              }.toMap
              canonicalRows += CanonicalFormRow(key, fields)
      }

      payload.recognizedKeys.filterNot(payload.presenceCounts.contains).foreach { key =>
        addStructural(
          FormError(
            rows.address,
            FieldIssue(s"Row '$key' has no valid presence control", Some("missing_row_presence"))
          )
        )
      }
      rows.address -> CanonicalFormGroup(canonicalRows.result())
    }.toMap

    val values = new FormValues[Owner, self.type](
      this,
      staticValues.view.mapValues(_.toVector).toMap,
      canonicalGroups
    )
    val includedAddresses = canonicalGroups.iterator.flatMap { case (groupAddress, group) =>
      group.rows.iterator.flatMap { row =>
        val rowAddress = FormAddress.row(groupAddress, row.key)
        row.fields.keysIterator.map(relative => FormAddress.append(rowAddress, relative))
      }
    }.toSet
    val validUsed = ordinaryUsed.filter { address =>
      staticValues.contains(address) || includedAddresses.contains(address)
    }.toSet
    FormProjection(values, structural.toVector, validUsed -- explicitlyUnused)
  end project

  private[scalive] def decodeRow[Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    row: CanonicalFormRow
  ): Either[FormErrors[Owner], Row] =
    val errors     = mutable.ArrayBuffer.empty[FormError[Owner]]
    val values     = Vector.newBuilder[Any]
    val rowAddress = FormAddress.row(rows.address, row.key)
    rows.typedFields.foreach { field =>
      val relative = FormDefinition.names(field.relativePath)
      field.decode(row.fields.getOrElse(relative, Vector.empty)) match
        case Right(value) => values += value
        case Left(issues) =>
          val address = FormAddress.append(rowAddress, relative)
          errors ++= issues.all.take(limits.maxErrors - errors.size).map(FormError(address, _))
    }
    val found = errors.toVector
    if found.nonEmpty then Left(FormErrors(found))
    else Right(rows.mirror.fromProduct(Tuple.fromArray(values.result().toArray)))

  private def decode(
    values: Values,
    structuralErrors: Vector[FormError[Owner]]
  ): Either[FormErrors[Owner], Domain] =
    val errors = mutable.ArrayBuffer.empty[FormError[Owner]]
    errors ++= structuralErrors.take(limits.maxErrors)
    val decoded = Vector.newBuilder[Any]

    parts.foreach {
      case untyped: FormField[?, ?, ?] =>
        val field = untyped.asInstanceOf[FormField[Owner, Any, Any]]
        field.decode(values.static.getOrElse(field.address, Vector.empty)) match
          case Right(value) => decoded += value
          case Left(issues) =>
            errors ++= issues.all
              .take(limits.maxErrors - errors.size).map(FormError(field.address, _))
      case untyped: RepeatedRows[?, ?, ?] =>
        val rows      = untyped.asInstanceOf[RepeatedRows[Owner, Any, Any]]
        val group     = values.groups.getOrElse(rows.address, CanonicalFormGroup(Vector.empty))
        val rowValues = Vector.newBuilder[Any]
        var valid     = true
        group.rows.foreach { row =>
          decodeRow(rows, row) match
            case Right(value) => rowValues += value
            case Left(found)  =>
              valid = false
              errors ++= found.all.take(limits.maxErrors - errors.size)
        }
        if valid then decoded += rowValues.result()
    }

    val found = errors.toVector
    if found.nonEmpty then Left(FormErrors(found))
    else
      construct(decoded.result()).left.map { produced =>
        val allowed = Set(rootAddress) ++ staticFields.map(_.address) ++ rowGroups.map(_.address)
        if produced.all.forall(error => allowed.contains(error.address)) then
          FormErrors(produced.all.take(limits.maxErrors))
        else
          FormErrors.one(
            rootAddress,
            FieldIssue(
              "Product validation targeted an undeclared form address",
              Some("undeclared_error_address")
            )
          )
      }
  end decode

  private def initialValues(
    assignments: Vector[FormInitial[Owner]]
  ): (Map[FormAddress[Owner], Vector[String]], Map[FormAddress[Owner], CanonicalFormGroup]) =
    val fields = assignments.collect { case value: FormFieldAssignment[Owner] => value }
    val groups = assignments.collect { case value: FormGroupAssignment[Owner, ?] => value }
    require(fields.forall(value => owns(value.field)), "initial field is not declared by this form")
    require(
      groups.forall(value => rowGroups.exists(_ eq value.rows)),
      "initial group is not declared"
    )
    require(fields.groupBy(_.field).forall(_._2.size == 1), "duplicate initial field assignment")
    require(groups.groupBy(_.rows).forall(_._2.size == 1), "duplicate initial group assignment")
    require(
      fields.forall(_.values.size <= limits.maxValuesPerField),
      "initial field exceeds maxValuesPerField"
    )

    val static = fields.iterator
      .filter(_.values.nonEmpty).map(value => value.field.address -> value.values).toMap
    val grouped = groups.iterator.map { assignment =>
      val rows   = assignment.rows.asInstanceOf[RepeatedRows[Owner, Any, ?]]
      val values = assignment.values.asInstanceOf[Vector[FormRowInitial[Any]]]
      val keys   = values.map(_.key.value)
      require(values.size <= limits.maxRowsPerGroup, "initial group exceeds maxRowsPerGroup")
      require(
        values.forall(_.assignments.forall(_.values.size <= limits.maxValuesPerField)),
        "initial row field exceeds maxValuesPerField"
      )
      require(keys.distinct.size == keys.size, "duplicate initial row key")
      val canonical = values.map { row =>
        val fields = row.assignments.iterator
          .filter(_.values.nonEmpty).map { value =>
            FormDefinition.names(value.field.relativePath) -> value.values
          }.toMap
        CanonicalFormRow(row.key.value, fields)
      }
      rows.address -> CanonicalFormGroup(canonical)
    }.toMap
    val absentGroups = rowGroups.iterator.map(_.address -> CanonicalFormGroup(Vector.empty)).toMap
    static -> (absentGroups ++ grouped)
  end initialValues

  private def staticUnusedAddress(names: Vector[String]): Option[FormAddress[Owner]] =
    names.lastOption.filter(_.startsWith(FormDefinition.UnusedPrefix)).flatMap { marker =>
      val actual = marker.stripPrefix(FormDefinition.UnusedPrefix)
      staticByPath.get(names.init :+ actual).map(_.address)
    }

  private def rowUnusedAddress(
    rows: RepeatedRows[Owner, ?, ?],
    key: String,
    leaf: Vector[String]
  ): Option[FormAddress[Owner]] =
    leaf.lastOption.filter(_.startsWith(FormDefinition.UnusedPrefix)).flatMap { marker =>
      val actual = leaf.init :+ marker.stripPrefix(FormDefinition.UnusedPrefix)
      rows.typedFields.find(field => FormDefinition.names(field.relativePath) == actual).map {
        field =>
          rowFieldAddress(rows, key, field)
      }
    }

  private def rowFieldAddress(
    rows: RepeatedRows[Owner, ?, ?],
    key: String,
    field: FormField[?, ?, ?]
  ): FormAddress[Owner] =
    FormAddress.append(
      FormAddress.row(rows.address, key),
      FormDefinition.names(field.relativePath)
    )

  private def addressForBrowserPath(path: FormPath, values: Values): Option[FormAddress[Owner]] =
    FormDefinition.namesOption(path).flatMap { names =>
      staticByPath.get(names).map(_.address).orElse {
        rowGroups.iterator
          .flatMap { rows =>
            val prefix = FormDefinition.names(rows.path)
            val rest   = names.drop(prefix.length)
            Option
              .when(names.startsWith(prefix) && rest.length >= 2) {
                val key      = rest.head
                val relative = rest.tail
                values.groups.get(rows.address).flatMap(_.rows.find(_.key == key)).flatMap { _ =>
                  rows.typedFields
                    .find(field => FormDefinition.names(field.relativePath) == relative).map {
                      field => rowFieldAddress(rows, key, field)
                    }
                }
              }.flatten
          }.nextOption()
      }
    }
end FormDefinition

private[scalive] object FormDefinition:
  val ReservedPrefix   = "_scalive_"
  val RowPresenceName  = "_scalive_row"
  val RowPresenceValue = "1"
  val UnusedPrefix     = "_unused_"

  final class GroupPayload:
    val presence        = mutable.ArrayBuffer.empty[(String, String)]
    val presenceCounts  = mutable.Map.empty[String, Int]
    val invalidPresence = mutable.Set.empty[String]
    val leaves = mutable.LinkedHashMap.empty[(String, Vector[String]), mutable.ArrayBuffer[String]]
    val recognizedKeys = mutable.LinkedHashSet.empty[String]
    val discoveredKeys = mutable.LinkedHashSet.empty[String]
    val valueOverflow  = mutable.Set.empty[(String, Vector[String])]
    var rowsOverflow   = false

  def GroupPayload(): GroupPayload = new GroupPayload

  def create[Owner, Domain](
    owner: FormRoot,
    parts: Vector[FormPart[?, ?]],
    mirror: Mirror.ProductOf[Domain]
  ): FormDefinition[Owner, Domain] =
    val construct: Vector[Any] => Either[FormErrors[Owner], Domain] = values =>
      Right(mirror.fromProduct(Tuple.fromArray(values.toArray)))
    new FormDefinition(owner.path, owner, parts, construct, FormLimits())

  def names(path: FormPath): Vector[String] =
    namesOption(path).getOrElse(
      throw new IllegalArgumentException(s"schema path ${path.name} contains an array segment")
    )

  def namesOption(path: FormPath): Option[Vector[String]] =
    path.segments.foldLeft(Option(Vector.empty[String])) {
      case (Some(found), FormPathSegment.Name(value)) => Some(found :+ value)
      case _                                          => None
    }

  def validateSchema(owner: FormRoot, parts: Vector[FormPart[?, ?]]): Unit =
    val allPaths = Vector.newBuilder[Vector[String]]
    parts.foreach {
      case field: FormField[?, ?, ?] =>
        field.scope match
          case FormFieldScope.Static(root, _) =>
            require(root eq owner, "form field belongs to another root")
          case _ => throw new IllegalArgumentException("row field cannot be a root form part")
        val relative = names(field.relativePath)
        require(!relative.exists(_.startsWith(ReservedPrefix)), "reserved _scalive_ field name")
        allPaths += names(field.path)
      case rows: RepeatedRows[?, ?, ?] =>
        require(rows.group.root eq owner, "repeated group belongs to another root")
        val groupPath     = names(rows.path)
        val relativeGroup = groupPath.drop(names(owner.path).length)
        require(
          !relativeGroup.exists(_.startsWith(ReservedPrefix)),
          "reserved _scalive_ group name"
        )
        allPaths += groupPath
        val rowPaths = rows.typedFields.map(field => names(field.relativePath))
        require(rowPaths.distinct.size == rowPaths.size, "duplicate row field address")
        require(
          !rowPaths.flatten.exists(_.startsWith(ReservedPrefix)),
          "reserved _scalive_ row field name"
        )
    }
    val paths = allPaths.result()
    require(paths.distinct.size == paths.size, "duplicate form part address")
    val conflicts = for
      left  <- paths
      right <- paths
      if left != right && (left.startsWith(right) || right.startsWith(left))
    yield left -> right
    require(conflicts.isEmpty, "overlapping scalar field and repeated group addresses")
  end validateSchema
end FormDefinition
