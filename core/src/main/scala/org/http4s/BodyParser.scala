package org.http4s

import scala.language.reflectiveCalls
import play.api.libs.iteratee._
import java.io._
import xml.{Elem, XML, NodeSeq}
import org.xml.sax.{SAXException, InputSource}
import javax.xml.parsers.SAXParser
import scala.util.{Success, Try}
import akka.util.ByteString

object BodyParser {
  val DefaultMaxEntitySize = Http4sConfig.getInt("org.http4s.default-max-entity-size")

  private val BodyChunkConsumer: Iteratee[BodyChunk, BodyChunk] = Iteratee.consume[BodyChunk]()

  def text[A](request: RequestPrelude, limit: Int = DefaultMaxEntitySize)(f: String => Responder): Iteratee[HttpChunk, Responder] =
    consumeUpTo(BodyChunkConsumer, limit) { bs => f(bs.decodeString(request.charset)) }

  /**
   * Handles a request body as XML.
   *
   * TODO Not an ideal implementation.  Would be much better with an asynchronous XML parser, such as Aalto.
   *
   * @param request the request prelude
   * @param limit the maximum size before an EntityTooLarge error is returned
   * @param parser the SAX parser to use to parse the XML
   * @param f a function of the XML body to the responder
   * @return a request handler
   */
  def xml(request: RequestPrelude,
          limit: Int = DefaultMaxEntitySize,
          parser: SAXParser = XML.parser,
          onSaxException: SAXException => Responder = { saxEx => saxEx.printStackTrace(); Status.BadRequest() })
         (f: Elem => Responder): Iteratee[HttpChunk, Responder] =
    consumeUpTo(BodyChunkConsumer, limit) { bytes =>
      val in = bytes.iterator.asInputStream
      val source = new InputSource(in)
      source.setEncoding(request.charset.name)
      Try(XML.loadXML(source, parser)).map(f).recover {
        case e: SAXException => onSaxException(e)
      }.get
    }

  def consumeUpTo[A](consumer: Iteratee[BodyChunk, A], limit: Int)(f: A => Responder): Iteratee[HttpChunk, Responder] =
    whileBody &>>
      (for {
        bytes <- Traversable.takeUpTo[BodyChunk](limit) &>> consumer
        tooLargeOrBytes <- Iteratee.eofOrElse(Status.RequestEntityTooLarge())(bytes)
      } yield (tooLargeOrBytes.right.map(f).merge))

  // TODO This can probably be optimized by directly implementing Enumeratee.  This may be worth doing,
  // because this is going to be a very common case.
  def whileBody = Enumeratee.takeWhile[HttpChunk](_.isInstanceOf[BodyChunk]) ><>
    Enumeratee.map[HttpChunk](_.asInstanceOf[BodyChunk])

  // File operations
  def binFile(file: java.io.File)(f: => Responder): Iteratee[HttpChunk,Responder] = {
    val out = new java.io.FileOutputStream(file)
    whileBody &>> Iteratee.foreach[BodyChunk]{ d => out.write(d.toArray) }.map{ _ => out.close(); f }
  }

  def textFile(req: RequestPrelude, in: java.io.File)(f: => Responder): Iteratee[HttpChunk,Responder] = {
    val is = new java.io.PrintStream(new FileOutputStream(in))
    whileBody &>> Iteratee.foreach[BodyChunk]{ d => is.print(d.decodeString(req.charset)) }.map{ _ => is.close(); f }
  }
}