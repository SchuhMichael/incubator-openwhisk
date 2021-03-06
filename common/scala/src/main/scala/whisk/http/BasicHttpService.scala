/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.http

import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.http.scaladsl.server.directives._
import akka.stream.ActorMaterializer
import spray.json._
import whisk.common._
import whisk.core.WhiskConfig

/**
 * This trait extends the Akka Directives and Actor with logging and transaction counting
 * facilities common to all OpenWhisk REST services.
 */
trait BasicHttpService extends Directives with TransactionCounter {

  val OW_EXTRA_LOGGING_HEADER = "X-OW-EXTRA-LOGGING"

  /**
   * Gets the routes implemented by the HTTP service.
   *
   * @param transid the id for the transaction (every request is assigned an id)
   */
  def routes(implicit transid: TransactionId): Route

  /**
   * Gets the log level for a given route. The default is
   * InfoLevel so override as needed.
   *
   * @param route the route to determine the loglevel for
   * @return a log level for the route
   */
  def loglevelForRoute(route: String): Logging.LogLevel = Logging.InfoLevel

  /** Prioritized rejections based on relevance. */
  val prioritizeRejections = recoverRejections { rejections =>
    val priorityRejection = rejections.find {
      case rejection: UnacceptedResponseContentTypeRejection => true
      case _                                                 => false
    }

    priorityRejection.map(rejection => Rejected(Seq(rejection))).getOrElse(Rejected(rejections))
  }

  /**
   * Receives a message and runs the router.
   */
  def route: Route = {
    assignId { implicit transid =>
      DebuggingDirectives.logRequest(logRequestInfo _) {
        DebuggingDirectives.logRequestResult(logResponseInfo _) {
          BasicDirectives.mapRequest(_.removeHeader(OW_EXTRA_LOGGING_HEADER)) {
            handleRejections(BasicHttpService.customRejectionHandler) {
              prioritizeRejections {
                toStrictEntity(30.seconds) {
                  routes
                }
              }
            }
          }
        }
      }
    }
  }

  /** Assigns transaction id to every request. */
  protected def assignId = HeaderDirectives.optionalHeaderValueByName(OW_EXTRA_LOGGING_HEADER) flatMap { headerValue =>
    val extraLogging = headerValue match {
      // extract headers from HTTP request that indicates if additional logging should be enabled for this transaction.
      // Passing "on" as header content will enable additional logging for this transaction,
      // passing any other value will leave it as configured in the logging configuration
      case Some(value) => value.toLowerCase == "on"
      case None        => false
    }
    extract(_ => transid(extraLogging))
  }

  /** Generates log entry for every request. */
  protected def logRequestInfo(req: HttpRequest)(implicit tid: TransactionId): LogEntry = {
    val m = req.method.name
    val p = req.uri.path.toString
    val q = req.uri.query().toString
    val l = loglevelForRoute(p)
    LogEntry(s"[$tid] $m $p $q", l)
  }

  protected def logResponseInfo(req: HttpRequest)(implicit tid: TransactionId): RouteResult => Option[LogEntry] = {
    case RouteResult.Complete(res: HttpResponse) =>
      val m = req.method.name
      val p = req.uri.path.toString
      val l = loglevelForRoute(p)

      val name = "BasicHttpService"

      val token =
        LogMarkerToken(
          "http",
          m.toLowerCase,
          LoggingMarkers.count,
          Some(res.status.intValue.toString),
          Map("statusCode" -> res.status.intValue.toString))
      val marker = LogMarker(token, tid.deltaToStart, Some(tid.deltaToStart))

      MetricEmitter.emitHistogramMetric(token, tid.deltaToStart)
      MetricEmitter.emitCounterMetric(token)

      if (TransactionId.metricsLog) {
        Some(LogEntry(s"[$tid] [$name] $marker", l))
      } else {
        None
      }

    case _ => None // other kind of responses
  }
}

object BasicHttpService {

  /**
   * Starts an HTTPS route handler on given port and registers a shutdown hook.
   */
  def startHttpsService(route: Route, port: Int, config: WhiskConfig)(implicit actorSystem: ActorSystem,
                                                                      materializer: ActorMaterializer): Unit = {

    implicit val executionContext = actorSystem.dispatcher

    val httpsBinding = Http().bindAndHandle(route, "0.0.0.0", port, connectionContext = Https.connectionContext(config))
    addShutdownHook(httpsBinding)
  }

  /**
   * Starts an HTTP route handler on given port and registers a shutdown hook.
   */
  def startHttpService(route: Route, port: Int)(implicit actorSystem: ActorSystem,
                                                materializer: ActorMaterializer): Unit = {
    val httpBinding = Http().bindAndHandle(route, "0.0.0.0", port)
    addShutdownHook(httpBinding)
  }

  def addShutdownHook(binding: Future[Http.ServerBinding])(implicit actorSystem: ActorSystem,
                                                           materializer: ActorMaterializer): Unit = {
    implicit val executionContext = actorSystem.dispatcher
    sys.addShutdownHook {
      Await.result(binding.map(_.unbind()), 30.seconds)
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
    }
  }

  /** Rejection handler to terminate connection on a bad request. Delegates to Akka handler. */
  def customRejectionHandler(implicit transid: TransactionId) = {
    RejectionHandler.default.mapRejectionResponse {
      case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
        val error = ErrorResponse(ent.data.utf8String, transid).toJson
        res.copy(entity = HttpEntity(ContentTypes.`application/json`, error.compactPrint))
      case x => x
    }
  }

}
