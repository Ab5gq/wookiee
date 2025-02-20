/*
 *  Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.oracle.infy.wookiee.component.akkahttp.websocket

import akka.actor.{Actor, ActorRef, Props, Status, Terminated}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.Supervision.Directive
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{CompletionStrategy, Materializer, Supervision}
import com.oracle.infy.wookiee.component.akkahttp.routes.{
  AkkaHttpEndpointRegistration,
  AkkaHttpRequest,
  EndpointOptions
}
import com.oracle.infy.wookiee.component.akkahttp.websocket.AkkaHttpWebsocket.WSFailure
import com.oracle.infy.wookiee.logging.LoggingAdapter

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

object AkkaHttpWebsocket {
  case class WSFailure(error: Throwable)
}

class AkkaHttpWebsocket[I: ClassTag, O <: Product: ClassTag, A <: Product: ClassTag](
    authHolder: A,
    textToInput: (A, TextMessage.Strict) => Future[I],
    handleInMessage: (I, WebsocketInterface[I, O, A]) => Unit,
    outputToText: O => TextMessage.Strict,
    onClose: (A, Option[I]) => Unit = { (_: A, _: Option[I]) =>
      ()
    },
    errorHandler: PartialFunction[Throwable, Directive] = AkkaHttpEndpointRegistration.wsErrorDefaultHandler,
    options: EndpointOptions = EndpointOptions.default
)(implicit ec: ExecutionContext, mat: Materializer)
    extends LoggingAdapter {
  var closed: AtomicBoolean = new AtomicBoolean(false)

  // This the the main method to route WS messages
  def websocketHandler(req: AkkaHttpRequest): Flow[Message, Message, Any] = {

    val socketActor = mat.system.actorOf(socketActorProps())
    val sink =
      Flow[Message]
        .mapAsync(1) {
          case tm: TextMessage if tm.isStrict =>
            tryWrap(textToInput(authHolder, TextMessage(tm.getStrictText)))
          case bm: BinaryMessage if bm.isStrict =>
            tryWrap(textToInput(authHolder, TextMessage(bm.getStrictData.utf8String)))
          case m: TextMessage =>
            m.toStrict(15.seconds).flatMap { tm =>
              tryWrap(textToInput(authHolder, TextMessage(tm.getStrictText)))
            }
          case bm: BinaryMessage =>
            bm.toStrict(15.seconds).flatMap { bmt =>
              tryWrap(textToInput(authHolder, TextMessage(bmt.getStrictData.utf8String)))
            }
        }
        .to(Sink.actorRef(socketActor, CloseSocket(), { err: Throwable =>
          WSFailure(err)
        }))

    val source: Source[Message, Unit] =
      Source
        .actorRefWithBackpressure[Message](
          MessageAck(),
          completionStrategy,
          failureStrategy
        )
        .mapMaterializedValue { outgoingActor =>
          socketActor ! Connect(outgoingActor)
        }

    Flow.fromSinkAndSourceCoupled(sink, source)
  }

  protected def socketActorProps(): Props = Props(new SocketActor())

  private def close(lastInput: Option[I]): Unit =
    if (!closed.getAndSet(true))
      onClose(authHolder, lastInput)

  private def completionStrategy: PartialFunction[Any, CompletionStrategy] = {
    case Status.Success(s: CompletionStrategy) =>
      log.debug(s"Stopping websocket with strategy [$s]")
      s
    case Status.Success(_) =>
      log.debug(s"Stopping websocket immediately")
      CompletionStrategy.immediately
  }

  private def failureStrategy: PartialFunction[Any, Throwable] = {
    case Status.Failure(cause) => cause
  }

  private def tryWrap(input: => Future[I]): Future[Any] =
    Try(input)
      .recover({
        case err: Throwable =>
          Future.successful(WSFailure(err))
      })
      .get

  case class CloseSocket() // We get this when websocket closes
  case class Connect(actorRef: ActorRef) // Initial connection
  case class MessageAck() // Arbitrary class we send back after each message to enable backpressure
  case class SendNext()

  // Actor that exists per each open websocket and closes when the WS closes, also routes back return messages
  class SocketActor() extends Actor {
    private[websocket] var lastInput: Option[I] = None //scalafix:ok
    val blockingQueue: ArrayBlockingQueue[TextMessage] =
      new ArrayBlockingQueue[TextMessage](100000)

    override def postStop(): Unit = {
      blockingQueue.clear()
      close(lastInput)
      super.postStop()
    }

    def receive: Receive = starting

    def starting: Receive = {
      case Connect(outgoingActor) =>
        context.become(
          open(
            outgoingActor,
            new WebsocketInterface[I, O, A](
              outgoingActor,
              authHolder,
              lastInput,
              outputToText,
              errorHandler,
              blockingQueue
            )
          )
        ) // Set callback actor
        context.watch(outgoingActor)
        self ! SendNext()
        ()
      case _: CloseSocket =>
        context.stop(self)
      case WSFailure(err) =>
        log.warn("Unexpected error caused websocket to close", err)
        context.stop(self)
    }

    // When becoming this, outgoingActor should already be set
    def open(outgoingActor: ActorRef, interface: WebsocketInterface[I, O, A]): Receive = {
      case input: I =>
        lastInput = Some(input)
        Future { // Don't block so we can get other messages
          handleInMessage(
            input,
            interface
          )
        }
        ()

      case SendNext() | MessageAck() =>
        sendNext(outgoingActor)

      case Terminated(actor) =>
        if (outgoingActor.path.equals(actor.path)) {
          log.debug(s"Linked outgoing actor terminated ${actor.path.name}, closing down websocket")
          context.stop(self)
        }

      case _: CloseSocket =>
        log.debug("CloseSocket message received")
        context.stop(self)

      case WSFailure(err) =>
        if (errorHandler.isDefinedAt(err)) {
          errorHandler(err) match {
            case Supervision.Stop =>
              log.info("Stopping Stream due to error that directed us to Supervision.Stop")
              context.stop(self)
            case Supervision.Resume => // Skip this event
            case Supervision.Restart => // Treat like Resume
              log.info("No support for Supervision.Restart yet, use either Resume or Stop")
          }
        } else {
          log.warn("Unexpected error caused websocket to close", err)
          context.stop(self)
        }
      case _ => // Mainly for eating the keep alive
    }

    def sendNext(outgoingActor: ActorRef): Unit = Option(blockingQueue.poll()) match {
      case Some(nextMessage) =>
        outgoingActor ! nextMessage

      case None =>
        context.system.scheduler.scheduleOnce(50.millis, self, SendNext())
        ()
    }
  }
}
