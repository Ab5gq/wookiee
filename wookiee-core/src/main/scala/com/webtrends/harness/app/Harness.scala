/*
 * Copyright 2015 Webtrends (http://www.webtrends.com)
 *
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webtrends.harness.app

import akka.actor.{ActorRef, ActorSystem, Props, UnhandledMessage}
import akka.pattern._
import com.typesafe.config.Config
import com.webtrends.harness.UnhandledEventListener
import com.webtrends.harness.app.HarnessActor.ShutdownSystem
import com.webtrends.harness.logging.Logger

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

/**
 * @author Michael Cuthbert on 10/30/14.
 */
object Harness {
  protected[harness] var system: Option[ActorSystem] = None
  def getActorSystem = system
  protected[harness] var log: Logger = Logger(this.getClass)
  def getLogger = log
  protected[harness] var rootActor: Option[ActorRef] = None
  def getRootActor = rootActor
  val externalLogger = Logger.getLogger(this.getClass)

  /**
   * Restart the actor system
   */
  def restartActorSystem = {
    log.info("Restarting the actor system")
    system match {
      case Some(_) =>
        shutdownActorSystem(block = false) {
          startActorSystem()
        }
      case _ =>
        externalLogger.info("There is no actor system so starting up now")
        startActorSystem()
    }
  }

  /**
   * Force a shutdown of the ActorSystem and the application's process.
   */
  def shutdown() = {
    log.info("Shutting down Wookiee")
    new Thread("lifecycle") {
      override def run() {
        Thread.sleep(10)
        shutdownActorSystem(block = false) {
          System.exit(0)
        }
      }
    }.start()
  }

  /**
   * Start the actor system
   */
  def startActorSystem(config:Option[Config]=None) = {
    try {
      externalLogger.debug("Creating the actor system")
      system = config match {
        case None => Some(HarnessActorSystem())
        case c => Some(HarnessActorSystem(c))
      }
      // add the unhandled message listener so we can debug messages easily that are not being handled
      val listener = system.get.actorOf(Props(new UnhandledEventListener))
      system.get.eventStream.subscribe(listener, classOf[UnhandledMessage])

      log = Logger(this.getClass, system.get)
      log.debug("Creating main Wookiee actor")
      implicit val sys = system.get
      rootActor = Some(system.get.actorOf(HarnessActor.props, "system"))
    }
    catch {
      case t: Throwable =>
        externalLogger.error(s"The actor system could not be started: ${t.getMessage}", t)
        sys.exit(0)
    }
  }

  /**
   * Shutdown the actor system
   */
  def shutdownActorSystem(block: Boolean)(f: => Unit) = {
    log.debug("Shutting down the main actor")
    import scala.concurrent.ExecutionContext.Implicits.global

    // We will tell the main actor that we are shutting down. This allows it to shutdown
    // its children and perform any needed cleanup.
    rootActor foreach { root =>
      val fut = gracefulStop(root, 15 seconds, ShutdownSystem)
        .andThen {
          case Success(_) =>
            log.debug("Now shutting down the the system itself")
        }
        // Now shutdown the system
        .flatMap(_ => system.map(_.terminate()).getOrElse(Future.successful(true)))

      fut.onComplete {
        case Success(_) =>
          // Set our flags
          system = None
          rootActor = None
          externalLogger.debug("The actor system has terminated")
          // Call the passed function
          f
        case Failure(reason) =>
          log.error("We were unable to properly shutdown the main actor", reason)
          System.exit(0)
      }

      if (block) {
        Await.result(fut, 15 seconds)
      }
    }
  }

  /**
   * Add a shutdown hook so when the process is shut down that we cleanup cleanly
   */
  def addShutdownHook(): Unit = {
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run() = {
        system match {
          case Some(sys) =>
            sys.log.debug("The shutdown hook has been called")
            shutdownActorSystem(block = true) {
              externalLogger.info("Wookiee Shut Down, Thanks for Coming!")
            }
          case _ =>
        }
      }
    }))
  }
}
