package scalive

import scala.compiletime.summonInline
import scala.deriving.Mirror

/** A schema part contributing one value to an enclosing product. */
trait FormPart[Owner, Value]

/** The value types contributed by a tuple of form parts owned by `Owner`. */
type FormPartValues[Owner, Parts <: Tuple] <: Tuple = Parts match
  case EmptyTuple                          => EmptyTuple
  case FormPart[Owner, value] *: remaining => value *: FormPartValues[Owner, remaining]

private[scalive] enum FormFieldScope:
  case Static(root: FormRoot, absolutePath: FormPath)
  case Row(group: AnyRef, root: FormRoot, groupPath: FormPath, relativePath: FormPath)

/** A field whose editable input and successfully refined value are separate types.
  *
  * Fields are identity-owned by the [[FormRoot]] or [[RepeatedGroup]] that created them. Structural
  * decoding is supplied by [[FieldInput]]; use [[emap]] for semantic refinement.
  */
final class FormField[Owner, Input, Value] private[scalive] (
  private[scalive] val scope: FormFieldScope,
  private[scalive] val inputCodec: FieldInput[Input],
  private[scalive] val refine: Input => Either[FieldIssues, Value])
    extends FormPart[Owner, Value]:

  private[scalive] val relativePath: FormPath = scope match
    case FormFieldScope.Static(root, absolute) =>
      FormPath.fromSegments(absolute.segments.drop(root.path.segments.length))
    case FormFieldScope.Row(_, _, _, relative) => relative

  /** The static address or row-field template address. */
  val address: FormAddress[Owner] = scope match
    case FormFieldScope.Static(_, absolute)            => FormAddress.fromPath(absolute)
    case FormFieldScope.Row(_, _, groupPath, relative) =>
      FormAddress.fromPath(
        FormPath.fromSegments(groupPath.segments ++ relative.segments)
      )

  /** The complete static browser path, or relative row-field path for a row template. */
  def path: FormPath = scope match
    case FormFieldScope.Static(_, absolute)    => absolute
    case FormFieldScope.Row(_, _, _, relative) => relative

  /** Browser field name for this static field or row-field template. */
  def name: String = path.name

  /** Template DOM id; use [[FormFieldView.id]] for a concrete repeated row. */
  def id: String = address.id

  /** Maps a successfully refined value without changing its editable input representation. */
  def map[B](f: Value => B): FormField[Owner, Input, B] =
    FormField(scope, inputCodec, input => refine(input).map(f))

  /** Adds error-producing semantic refinement after structural input decoding. */
  def emap[B](f: Value => Either[FieldIssues, B]): FormField[Owner, Input, B] =
    FormField(scope, inputCodec, input => refine(input).flatMap(f))

  /** Retains values satisfying `predicate` and otherwise reports `issue`. */
  def validate(issue: FieldIssue)(predicate: Value => Boolean): FormField[Owner, Input, Value] =
    emap(value => Either.cond(predicate(value), value, FieldIssues.one(issue)))

  /** Reports every issue produced for a structurally and semantically decoded value. */
  def validateAll(
    validate: Value => IterableOnce[FieldIssue]
  ): FormField[Owner, Input, Value] = emap { value =>
    val issues = validate(value).iterator.toVector
    if issues.isEmpty then Right(value) else Left(FieldIssues(issues))
  }

  /** Rejects an empty refined string while preserving the field's original input type. */
  def required(
    issue: FieldIssue = FieldIssue("can't be blank", Some("required"))
  )(using valueIsString: Value =:= String
  ): FormField[Owner, Input, String] =
    map(valueIsString).validate(issue)(_.nonEmpty)

  /** Encodes a typed editable input for initialization or row construction. */
  def initial(input: Input): FormFieldAssignment[Owner] =
    FormFieldAssignment(this, inputCodec.encode(input))

  /** Preserves explicit raw values for malformed-control tests and custom controls.
    *
    * Unlike [[initial]], this bypasses input encoding; validation still occurs when the form is
    * built.
    */
  def raw(values: Vector[String]): FormFieldAssignment[Owner] =
    FormFieldAssignment(this, values)

  private[scalive] def decodeInput(raw: Vector[String]): Either[FieldIssues, Input] =
    inputCodec.decode(raw)

  private[scalive] def decode(raw: Vector[String]): Either[FieldIssues, Value] =
    decodeInput(raw).flatMap(refine)
end FormField

private[scalive] object FormField:
  def apply[Owner, Input](scope: FormFieldScope, input: FieldInput[Input])
    : FormField[Owner, Input, Input] =
    new FormField(scope, input, Right(_))

  def apply[Owner, Input, Value](
    scope: FormFieldScope,
    input: FieldInput[Input],
    refine: Input => Either[FieldIssues, Value]
  ): FormField[Owner, Input, Value] = new FormField(scope, input, refine)

/** One owner-checked typed or raw field assignment for [[FormDefinition.initial]]. */
final class FormFieldAssignment[Owner] private[scalive] (
  private[scalive] val field: FormField[Owner, ?, ?],
  private[scalive] val values: Vector[String])
    extends FormInitial[Owner]

private[scalive] object FormFieldAssignment:
  def apply[Owner](field: FormField[Owner, ?, ?], values: Vector[String])
    : FormFieldAssignment[Owner] =
    new FormFieldAssignment(field, values)

/** An owner-checked initial contribution to a form or row. */
sealed trait FormInitial[Owner]

/** Declares a root-level form schema namespace.
  *
  * Keep the returned root as a stable value: its singleton type owns fields, initial assignments,
  * and the definition produced by [[product]].
  */
final class FormRoot private (val path: FormPath):
  self =>

  type Field[Input, Value] = FormField[self.type, Input, Value]
  type Initial             = FormInitial[self.type]

  /** Declares a field at a trusted relative path with a custom editable-input codec. */
  def field[Input](relative: String, input: FieldInput[Input]): Field[Input, Input] =
    FormField(FormFieldScope.Static(this, fullPath(relative)), input)

  /** Declares a single-value text field. */
  def text(
    relative: String,
    default: String = "",
    duplicateIssue: FieldIssue = FieldIssue(
      "must be submitted at most once",
      Some("duplicate_value")
    )
  ): Field[String, String] = field(relative, FieldInput.text(default, duplicateIssue))

  /** Declares a single-value optional text field. */
  def optionalText(
    relative: String,
    empty: String = "",
    duplicateIssue: FieldIssue = FieldIssue(
      "must be submitted at most once",
      Some("duplicate_value")
    )
  ): Field[Option[String], Option[String]] =
    field(relative, FieldInput.optionalText(empty, duplicateIssue))

  /** Declares a field that preserves every submitted value in order. */
  def texts(relative: String): Field[Vector[String], Vector[String]] =
    field(relative, FieldInput.texts)

  /** Opens an identity-scoped namespace for stable keyed repeated rows. */
  def rows(relative: String): RepeatedGroup[self.type] =
    new RepeatedGroup(this, fullPath(relative))

  /** Completes a product schema, checking at compile time that part values match `Domain`. */
  inline def product[Domain](
    parts: Tuple
  )(using
    mirror: Mirror.ProductOf[Domain]
  ): FormDefinition[self.type, Domain] =
    val _ = summonInline[FormPartValues[self.type, parts.type] =:= mirror.MirroredElemTypes]
    buildProduct(parts, mirror)

  private[scalive] def buildProduct[Domain](
    parts: Tuple,
    mirror: Mirror.ProductOf[Domain]
  ): FormDefinition[self.type, Domain] =
    FormDefinition.create(this, FormRoot.parts(parts), mirror)

  private[scalive] def fullPath(relative: String): FormPath =
    val parsed = FormRoot.relativePath(relative)
    FormPath.fromSegments(path.segments ++ parsed.segments)
end FormRoot

/** Constructor and internal validation support for [[FormRoot]]. */
object FormRoot:
  /** Creates a root namespace from a trusted browser path without array segments. */
  def apply(name: String): FormRoot =
    val path = FormPath
      .parse(name).fold(
        error => throw new IllegalArgumentException(s"invalid form root '$name': ${error.code}"),
        identity
      )
    require(path.nonEmpty, "form root must not be empty")
    require(
      path.segments.forall(_.isInstanceOf[FormPathSegment.Name]),
      "form root must not contain array segments"
    )
    new FormRoot(path)

  private[scalive] def relativePath(value: String): FormPath =
    val path = FormPath
      .parse(value).fold(
        error => throw new IllegalArgumentException(s"invalid form path '$value': ${error.code}"),
        identity
      )
    require(path.nonEmpty, "form path must not be empty")
    require(
      path.segments.forall(_.isInstanceOf[FormPathSegment.Name]),
      "schema paths must not contain array segments"
    )
    path

  private def parts(tuple: Tuple): Vector[FormPart[?, ?]] =
    tuple.productIterator.map {
      case part: FormPart[?, ?] => part
      case _ => throw new IllegalArgumentException("form products may contain only form parts")
    }.toVector
end FormRoot

/** A row-field namespace tied to one exact repeated group value.
  *
  * Its singleton type prevents fields and [[FormRowKey]] values from being mixed across groups.
  */
final class RepeatedGroup[Owner] private[scalive] (
  val root: FormRoot,
  val path: FormPath):
  self =>

  type Key                 = FormRowKey[self.type]
  type Field[Input, Value] = FormField[self.type, Input, Value]
  type Initial             = FormInitial[self.type]

  /** Logical group address used for group-level validation. */
  val address: FormAddress[Owner] = FormAddress.fromPath(path)

  /** Declares a row-relative field with a custom editable-input codec. */
  def field[Input](relative: String, input: FieldInput[Input]): Field[Input, Input] =
    val relativePath = FormRoot.relativePath(relative)
    FormField(FormFieldScope.Row(this, root, path, relativePath), input)

  /** Declares a single-value row text field. */
  def text(
    relative: String,
    default: String = "",
    duplicateIssue: FieldIssue = FieldIssue(
      "must be submitted at most once",
      Some("duplicate_value")
    )
  ): Field[String, String] = field(relative, FieldInput.text(default, duplicateIssue))

  /** Declares a single-value optional row text field. */
  def optionalText(relative: String): Field[Option[String], Option[String]] =
    field(relative, FieldInput.optionalText())

  /** Declares a row field that preserves every submitted value in order. */
  def texts(relative: String): Field[Vector[String], Vector[String]] =
    field(relative, FieldInput.texts)

  /** Completes the row product and contributes a `Vector[Row]` to the root product. */
  inline def product[Row](
    parts: Tuple
  )(using
    mirror: Mirror.ProductOf[Row]
  ): RepeatedRows[Owner, self.type, Row] =
    val _ = summonInline[FormPartValues[self.type, parts.type] =:= mirror.MirroredElemTypes]
    buildProduct(parts, mirror)

  private[scalive] def buildProduct[Row](
    parts: Tuple,
    mirror: Mirror.ProductOf[Row]
  ): RepeatedRows[Owner, self.type, Row] =
    val fields = parts.productIterator.map {
      case field: FormField[?, ?, ?] => field
      case _ => throw new IllegalArgumentException("row products may contain only scalar fields")
    }.toVector
    new RepeatedRows(this, fields, mirror)
end RepeatedGroup

/** A completed repeated-row schema and root-level product part.
  *
  * Rows are identified by group-scoped [[FormRowKey]] values rather than vector positions.
  */
final class RepeatedRows[Owner, Group, Row] private[scalive] (
  private[scalive] val group: RepeatedGroup[Owner],
  private[scalive] val fields: Vector[FormField[?, ?, ?]],
  private[scalive] val mirror: Mirror.ProductOf[Row])
    extends FormPart[Owner, Vector[Row]]:

  type Key = FormRowKey[Group]

  /** Logical group address; concrete row addresses add a keyed row segment. */
  val address: FormAddress[Owner] = group.address

  /** Browser path of the repeated group. */
  def path: FormPath = group.path

  private[scalive] val typedFields: Vector[FormField[Group, ?, ?]] =
    fields.map { field =>
      field.scope match
        case FormFieldScope.Row(owner, _, _, _) if owner eq group =>
          field.asInstanceOf[FormField[Group, ?, ?]]
        case _ =>
          throw new IllegalArgumentException("row field belongs to another repeated group")
    }

  /** Prepares one keyed row, rejecting assignments from another row schema. */
  def row(key: Key)(assignments: FormInitial[Group]*): FormRowInitial[Group] =
    FormRowInitial.create(this, key, assignments)

  /** Prepares the complete repeated-group contribution to [[FormDefinition.initial]]. */
  def initial(rows: FormRowInitial[Group]*): FormGroupAssignment[Owner, Group] =
    new FormGroupAssignment(this, rows.toVector)
end RepeatedRows

/** One keyed row prepared for form initialization or insertion. */
final class FormRowInitial[Group] private[scalive] (
  private[scalive] val key: FormRowKey[Group],
  private[scalive] val assignments: Vector[FormFieldAssignment[Group]])

private[scalive] object FormRowInitial:
  def create[Owner, Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    values: Seq[FormInitial[Group]]
  ): FormRowInitial[Group] =
    val assignments = values.map {
      case field: FormFieldAssignment[Group] => field
      case _ => throw new IllegalArgumentException("rows may contain only field assignments")
    }.toVector
    val declared = rows.typedFields.toSet
    require(assignments.forall(value => declared.contains(value.field)), "undeclared row field")
    val duplicates =
      assignments.groupBy(_.field).collect { case (field, found) if found.size > 1 => field }
    require(duplicates.isEmpty, "duplicate row field assignment")
    new FormRowInitial(key, assignments)

/** Initial values for one complete repeated group. */
final class FormGroupAssignment[Owner, Group] private[scalive] (
  private[scalive] val rows: RepeatedRows[Owner, Group, ?],
  private[scalive] val values: Vector[FormRowInitial[Group]])
    extends FormInitial[Owner]
