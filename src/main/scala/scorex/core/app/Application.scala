package scorex.core.app

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import scorex.core.{NodeViewHolder, PersistentNodeViewModifier}
import scorex.core.api.http.{ApiRoute, CompositeHttpService}
import scorex.core.network._
import scorex.core.network.message._
import scorex.core.network.peer.PeerManager
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.transaction.Transaction
import scorex.core.utils.ScorexLogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.runtime.universe.Type

trait Application extends ScorexLogging {

  type P <: Proposition
  type TX <: Transaction[P]
  type PMOD <: PersistentNodeViewModifier
  type NVHT <: NodeViewHolder[P, TX, PMOD]

  val ApplicationNameLimit = 50

  //settings
  implicit val settings: ScorexSettings

  //api
  val apiRoutes: Seq[ApiRoute]
  val apiTypes: Set[Class[_]]

  protected implicit lazy val actorSystem = ActorSystem(settings.network.agentName)

  protected val additionalMessageSpecs: Seq[MessageSpec[_]]

  //p2p
  lazy val upnp = new UPnP(settings.network)

  private lazy val basicSpecs = {
    val invSpec = new InvSpec(settings.network.maxInvObjects)
    val requestModifierSpec = new RequestModifierSpec(settings.network.maxInvObjects)
    Seq(
      GetPeersSpec,
      PeersSpec,
      invSpec,
      requestModifierSpec,
      ModifiersSpec
    )
  }

  lazy val messagesHandler: MessageHandler = MessageHandler(basicSpecs ++ additionalMessageSpecs)

  val nodeViewHolderRef: ActorRef
  val nodeViewSynchronizer: ActorRef
  val localInterface: ActorRef


  val peerManagerRef = actorSystem.actorOf(Props(new PeerManager(settings)))

  val nProps = Props(new NetworkController(settings.network, messagesHandler, upnp, peerManagerRef))
  val networkController = actorSystem.actorOf(nProps, "networkController")

  lazy val combinedRoute = CompositeHttpService(actorSystem, apiTypes, apiRoutes, settings.restApi).compositeRoute

  def run(): Unit = {
    require(settings.network.agentName.length <= ApplicationNameLimit)

    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at 0.0.0.0:${settings.restApi.port}")

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(combinedRoute, "0.0.0.0", settings.restApi.port)

    //on unexpected shutdown
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        log.error("Unexpected shutdown")
        stopAll()
      }
    })
  }

  def stopAll(): Unit = synchronized {
    log.info("Stopping network services")
    if (settings.network.upnpEnabled) upnp.deletePort(settings.network.port)
    networkController ! NetworkController.ShutdownNetwork

    log.info("Stopping actors (incl. block generator)")
    actorSystem.terminate().onComplete { _ =>

      log.info("Exiting from the app...")
      System.exit(0)
    }
  }
}
