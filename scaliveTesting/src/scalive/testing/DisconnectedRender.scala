package scalive.testing

import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import zio.*
import zio.http.*

import scalive.FormPath

object DisconnectedRender:
  def run[R](
    routes: Routes[R, Nothing],
    request: Request
  ): ZIO[R, Throwable, RenderedPage] =
    ZIO.scoped {
      routes.runZIO(request).flatMap { response =>
        response.body.asString.flatMap { html =>
          ZIO.attempt {
            val document           = Jsoup.parse(html, request.url.encode)
            val replayableResponse = response.copy(body = Body.fromString(html))
            RenderedPage(replayableResponse, html, document)
          }
        }
      }
    }

final case class FormQuery(
  action: Option[String] = None,
  method: Option[Method] = None)

enum FormQueryError:
  case NotFound(query: FormQuery)
  case MultipleMatches(query: FormQuery, count: Int)

final class RenderedPage private[testing] (
  val response: Response,
  val html: String,
  document: Document):

  def text: String = document.text()

  def forms: Vector[RenderedForm] =
    document.select("form").asScala.toVector.map(RenderedForm(_))

  def form(query: FormQuery = FormQuery()): Either[FormQueryError, RenderedForm] =
    val matches = forms.filter(renderedForm =>
      query.action.forall(renderedForm.action.contains) &&
        query.method.forall(_ == renderedForm.method)
    )

    matches match
      case Vector(form) => Right(form)
      case Vector()     => Left(FormQueryError.NotFound(query))
      case forms        => Left(FormQueryError.MultipleMatches(query, forms.size))

object RenderedPage:
  private[testing] def apply(
    response: Response,
    html: String,
    document: Document
  ): RenderedPage =
    new RenderedPage(response, html, document)

final class RenderedForm private[testing] (element: Element):
  def id: Option[String] = attribute("id")

  def action: Option[String] = attribute("action")

  def method: Method =
    if attribute("method").exists(_.equalsIgnoreCase("post")) then Method.POST else Method.GET

  def fields: Vector[RenderedField] =
    element
      .select("button[name], input[name], select[name], textarea[name]").asScala.toVector
      .map(RenderedField(_))

  def names: Vector[String] = fields.map(_.name)

  def values(name: String): Vector[String] =
    fields.collect { case field if field.name == name => field.value }

  def values(path: FormPath): Vector[String] =
    values(path.name)

  def hasChangeBinding: Boolean = element.hasAttr("phx-change")

  def hasSubmitBinding: Boolean = element.hasAttr("phx-submit")

  def triggersAction: Boolean = element.hasAttr("phx-trigger-action")

  private def attribute(name: String): Option[String] =
    Option.when(element.hasAttr(name))(element.attr(name))

object RenderedForm:
  private[testing] def apply(element: Element): RenderedForm =
    new RenderedForm(element)

final class RenderedField private[testing] (element: Element):
  def tagName: String = element.tagName()

  def id: Option[String] = attribute("id")

  def name: String = element.attr("name")

  def value: String = element.`val`()

  def inputType: Option[String] =
    Option.when(tagName == "input")(element.attr("type")).filter(_.nonEmpty)

  def required: Boolean = element.hasAttr("required")

  private def attribute(name: String): Option[String] =
    Option.when(element.hasAttr(name))(element.attr(name))

object RenderedField:
  private[testing] def apply(element: Element): RenderedField =
    new RenderedField(element)
