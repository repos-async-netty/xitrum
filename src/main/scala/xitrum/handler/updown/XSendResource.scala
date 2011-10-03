package xitrum.handler.updown

import java.io.{File, RandomAccessFile}

import org.jboss.netty.channel.{ChannelEvent, ChannelUpstreamHandler, ChannelDownstreamHandler, Channels, ChannelHandlerContext, DownstreamMessageEvent, UpstreamMessageEvent, ChannelFuture, DefaultFileRegion, ChannelFutureListener}
import org.jboss.netty.handler.codec.http.{HttpHeaders, HttpRequest, HttpResponse, HttpResponseStatus, HttpVersion}
import HttpResponseStatus._
import HttpVersion._
import HttpHeaders.Names._
import HttpHeaders.Values._
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.stream.ChunkedFile
import org.jboss.netty.buffer.ChannelBuffers

import xitrum.{Config, Logger}
import xitrum.etag.{Etag, NotModified}
import xitrum.util.Mime

object XSendResource extends Logger {
  val CHUNK_SIZE = 8 * 1024

  private val X_SENDRESOURCE_HEADER = "X-Sendresource"

  def setHeader(response: HttpResponse, path: String) {
    response.setHeader(X_SENDRESOURCE_HEADER, path)
  }

  /** @return false if not found */
  def sendResource(ctx: ChannelHandlerContext, e: ChannelEvent, request: HttpRequest, response: HttpResponse, path: String) {
    Etag.forResource(path) match {
      case Etag.NotFound => XSendFile.set404Page(response)

      case Etag.Small(bytes, etag, mimeo, gzipped) =>
        if (Etag.areEtagsIdentical(request, etag)) {
          response.setStatus(NOT_MODIFIED)
          HttpHeaders.setContentLength(response, 0)
          response.setContent(ChannelBuffers.EMPTY_BUFFER)
        } else {
          response.setHeader(ETAG, etag)
          if (mimeo.isDefined) response.setHeader(CONTENT_TYPE, mimeo.get)
          if (gzipped)         response.setHeader(CONTENT_ENCODING, "gzip")

          NotModified.setMaxAgeAggressively(response)

          val cb = ChannelBuffers.wrappedBuffer(bytes)
          HttpHeaders.setContentLength(response, bytes.length)
          response.setContent(cb)
        }
    }
    ctx.sendDownstream(e)
  }
}

/**
 * This handler sends resource files (should be small) in CLASSPATH.
 */
class XSendResource extends ChannelUpstreamHandler with ChannelDownstreamHandler {
  import XSendResource._

  var request: HttpRequest = _

  def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    if (!e.isInstanceOf[UpstreamMessageEvent]) {
      ctx.sendUpstream(e)
      return
    }

    val m = e.asInstanceOf[UpstreamMessageEvent].getMessage
    if (!m.isInstanceOf[HttpRequest]) {
      ctx.sendUpstream(e)
      return
    }

    request = m.asInstanceOf[HttpRequest]
    ctx.sendUpstream(e)
  }

  def handleDownstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    if (!e.isInstanceOf[DownstreamMessageEvent]) {
      ctx.sendDownstream(e)
      return
    }

    val m = e.asInstanceOf[DownstreamMessageEvent].getMessage
    if (!m.isInstanceOf[HttpResponse]) {
      ctx.sendDownstream(e)
      return
    }

    val response = m.asInstanceOf[HttpResponse]
    val path     = response.getHeader(X_SENDRESOURCE_HEADER)
    if (path == null) {
      ctx.sendDownstream(e)
      return
    }

    // X-SendResource is not standard, remove to avoid leaking information
    response.removeHeader(X_SENDRESOURCE_HEADER)
    sendResource(ctx, e, request, response, path)
  }
}