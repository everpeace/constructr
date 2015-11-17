/*
 * Copyright 2015 Heiko Seeberger
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

package de.heikoseeberger.constructr.cassandra

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, Terminated }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import de.heikoseeberger.constructr.coordination.Coordination
import de.heikoseeberger.constructr.machine.ConstructrMachine
import java.net.InetAddress

object Constructr {

  final val Name = "constructr-cassandra"

  case object GetNodes
  final case class Nodes(value: List[InetAddress])

  def props(strategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy): Props = Props(new Constructr(strategy))

  private def intoJoiningHandler(constructr: ActorRef)(machine: ConstructrMachine[InetAddress]): ConstructrMachine.TransitionHandler[InetAddress] = {
    case (_, ConstructrMachine.State.Joining) => constructr ! Constructr.Nodes(machine.nextStateData)
  }
}

final class Constructr private (override val supervisorStrategy: SupervisorStrategy)
    extends Actor with ActorLogging with ActorSettings {
  import Constructr._

  private val machine = context.watch(createConstructrMachine())

  private var nodes = Option.empty[Nodes]

  override def receive = waitingForNodes(Set.empty)

  private def waitingForNodes(requesters: Set[ActorRef]): Receive = receiveTerminated.orElse {
    case GetNodes =>
      context.become(waitingForNodes(requesters + sender()))
    case nodes: Nodes =>
      requesters.foreach(_ ! nodes)
      context.become(nodesReceived(nodes))
  }

  private def nodesReceived(nodes: Nodes): Receive = receiveTerminated.orElse {
    case GetNodes => sender() ! nodes
  }

  private def receiveTerminated: Receive = {
    case Terminated(`machine`) =>
      log.error("Terminating the system, because constructr-machine has terminated!")
      context.system.terminate()
  }

  private def createConstructrMachine() = {
    implicit val mat = ActorMaterializer()
    val send = Http()(context.system).singleRequest(_: HttpRequest)
    val coordination = Coordination(settings.coordination.backend)(
      "cassandra", settings.clusterName, settings.coordination.host, Integer.valueOf(settings.coordination.port), send
    )
    context.actorOf(
      ConstructrMachine.props(
        settings.selfAddress,
        coordination,
        settings.coordinationTimeout,
        settings.retryGetNodesDelay,
        settings.refreshInterval,
        settings.ttlFactor,
        None,
        intoJoiningHandler(self)
      ),
      ConstructrMachine.Name
    )
  }
}