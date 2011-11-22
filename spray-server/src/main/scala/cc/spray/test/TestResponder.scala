/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray
package test

import collection.mutable.ListBuffer
import http.{HttpResponse, HttpHeader, ChunkExtension, MessageChunk}
import akka.util.Duration
import java.util.concurrent.{TimeUnit, CountDownLatch}
import typeconversion.ChunkSender

/**
 * A RequestResponder using during testing.
 * It collects the response (incl. potentially generated response chunks) for later inspection in a test.
 */
class TestResponder(givenComplete: Option[HttpResponse => Unit] = None,
                    givenReject: Option[Set[Rejection] => Unit] = None) extends RequestResponder { outer =>
  var response: Option[HttpResponse] = None
  var rejections = Set.empty[Rejection]
  val chunks = ListBuffer.empty[MessageChunk]
  var closingExtensions: List[ChunkExtension] = Nil
  var trailer: List[HttpHeader] = Nil
  private val latch = new CountDownLatch(1)
  private var virginal = true

  val complete = givenComplete getOrElse { (resp: HttpResponse) =>
    saveResult(Right(resp))
    latch.countDown()
  }

  val reject = givenReject getOrElse { (rejs: Set[Rejection]) =>
    saveResult(Left(rejs))
    latch.countDown()
  }

  override def startChunkedResponse(response: HttpResponse) = {
    saveResult(Right(response))
    new TestChunkSender()
  }

  override def resetConnectionTimeout() {}

  def withComplete(newComplete: HttpResponse => Unit) = new TestResponder(Some(newComplete), Some(reject))

  def withReject(newReject: Set[Rejection] => Unit) = new TestResponder(Some(complete), Some(newReject))

  def withOnClientClose(callback: () => Unit) = this

  def awaitResult(timeout: Duration) {
    latch.await(timeout.toMillis, TimeUnit.MILLISECONDS)
  }

  private def saveResult(result: Either[Set[Rejection], HttpResponse]) {
    synchronized {
      if (!virginal) SprayTest.doFail("Route completed/rejected more than once")
      result match {
        case Right(resp) => response = Some(resp)
        case Left(rejs) => rejections = rejs
      }
      virginal = false
    }
  }

  class TestChunkSender(onSent: Option[Long => Unit] = None) extends ChunkSender {
    def sendChunk(chunk: MessageChunk) = outer.synchronized {
      chunks += chunk
      val chunkNr = if (response.get.content.isEmpty) chunks.size - 1 else chunks.size
      onSent.foreach(_(chunkNr))
      chunkNr
    }

    def close(extensions: List[ChunkExtension], trailer: List[HttpHeader]) {
      outer.synchronized {
        if (latch.getCount == 0) SprayTest.doFail("`close` called more than once")
        closingExtensions = extensions
        outer.trailer = trailer
        latch.countDown()
      }
    }

    def withOnChunkSent(callback: Long => Unit) = new TestChunkSender(Some(callback))
  }
}

