package scorex.core.network

import akka.actor.{Actor, ActorRef}
import scorex.core._
import scorex.core.NodeViewHolder._
import scorex.core.consensus.{History, SyncInfo}
import scorex.core.network.NetworkController.{DataFromPeer, SendToNetwork}
import scorex.core.network.message.{InvSpec, RequestModifierSpec, _}
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.Proposition

import scala.collection.mutable
import scorex.core.network.message.BasicMsgDataTypes._
import scorex.core.settings.NetworkSettings
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * A middle layer between a node view holder(NodeViewHolder) and the p2p network
  *
  * @param networkControllerRef
  * @param viewHolderRef
  * @param localInterfaceRef
  * @param syncInfoSpec
  * @tparam P
  * @tparam TX
  * @tparam SIS
  */
class NodeViewSynchronizer[P <: Proposition, TX <: Transaction[P], SI <: SyncInfo, SIS <: SyncInfoMessageSpec[SI]]
(networkControllerRef: ActorRef,
 viewHolderRef: ActorRef,
 localInterfaceRef: ActorRef,
 syncInfoSpec: SIS,
 networkSettings: NetworkSettings) extends Actor with ScorexLogging {

  import NodeViewSynchronizer._
  import History.HistoryComparisonResult._

  //todo: change with something like Bloom filters? make filters for asked and delivered objects?
  //modifier ids asked from other nodes are kept in order to check then
  //against objects delivered
  private val asked = mutable.Map[ModifierTypeId, mutable.Set[ModifierId]]()

  //todo: use it
  private val delivered = mutable.Map[ModifierId, ConnectedPeer]()

  private val seniors = mutable.Set[String]()
  private val juniors = mutable.Set[String]()
  private val equals = mutable.Set[String]()

  private val invSpec = new InvSpec(networkSettings.maxInvObjects)
  private val requestModifierSpec = new RequestModifierSpec(networkSettings.maxInvObjects)

  override def preStart(): Unit = {
    //register as a handler for some types of messages
    val messageSpecs = Seq(invSpec, requestModifierSpec, ModifiersSpec, syncInfoSpec)
    networkControllerRef ! NetworkController.RegisterMessagesHandler(messageSpecs, self)

    val events = Seq(
      NodeViewHolder.EventType.FailedTransaction,
      NodeViewHolder.EventType.SuccessfulTransaction,
      NodeViewHolder.EventType.SyntacticallyFailedPersistentModifier,
      NodeViewHolder.EventType.SemanticallyFailedPersistentModifier,
      NodeViewHolder.EventType.SuccessfulSyntacticallyValidModifier,
      NodeViewHolder.EventType.SuccessfulSemanticallyValidModifier
    )
    viewHolderRef ! Subscribe(events)

    context.system.scheduler.schedule(2.seconds, 15.seconds)(self ! GetLocalSyncInfo)
  }

  private def broadcastModifierInv[M <: NodeViewModifier](m: M): Unit = {
    val msg = Message(invSpec, Right(m.modifierTypeId -> Seq(m.id)), None)
    networkControllerRef ! SendToNetwork(msg, Broadcast)
  }

  private def viewHolderEvents: Receive = {
    case FailedTransaction(tx, throwable) =>
    //todo: ban source peer?
    case SyntacticallyFailedModification(mod, throwable) =>
    //todo: ban source peer?

    case SuccessfulTransaction(tx) => broadcastModifierInv(tx)
    case SyntacticallySuccessfulModifier(mod) => broadcastModifierInv(mod)
  }

  private def getLocalSyncInfo: Receive = {
    case GetLocalSyncInfo =>
      viewHolderRef ! NodeViewHolder.GetSyncInfo
  }

  //sending out sync message to a random peer
  private def syncSend: Receive = {
    case CurrentSyncInfo(syncInfo: SI) =>
      networkControllerRef ! SendToNetwork(Message(syncInfoSpec, Right(syncInfo), None), SendToRandom)
  }


  //sync info is coming from another node
  private def processSync: Receive = {
    case DataFromPeer(spec, syncData: SI, remote)
      if spec.messageCode == syncInfoSpec.messageCode =>

      viewHolderRef ! OtherNodeSyncingInfo(remote, syncData)
  }

  //view holder is telling other node status
  private def processSyncStatus: Receive = {
    case OtherNodeSyncingStatus(remote, status, remoteSyncInfo, localSyncInfo: SI, extOpt) =>
      if (!remoteSyncInfo.answer) {
        networkControllerRef ! SendToNetwork(Message(syncInfoSpec, Right(localSyncInfo), None), SendToRandom)
      }

      val seniorsBefore = seniors.size

      val remoteHost = remote.socketAddress.getAddress.getHostAddress

      seniors.remove(remoteHost)
      juniors.remove(remoteHost)
      equals.remove(remoteHost)

      status match {
        case Nonsense =>
          log.warn("Got nonsense")

        case Older =>
          seniors.add(remoteHost)

        case Younger =>
          juniors.add(remoteHost)
          if (extOpt.isEmpty) {
            log.warn("extOpt is empty for Younger brother")
          }
          val ext = extOpt.get
          ext.groupBy(_._1).mapValues(_.map(_._2)).foreach {
            case (mid, mods) =>
              networkControllerRef ! SendToNetwork(Message(invSpec, Right(mid -> mods), None), SendToPeer(remote))
          }
        case Equal =>
          equals.add(remoteHost)
      }

      val seniorsAfter = seniors.size

      if (seniorsBefore > 0 && seniorsAfter == 0) {
        localInterfaceRef ! LocalInterface.NoBetterNeighbour
      }

      if (seniorsBefore == 0 && seniorsAfter > 0) {
        localInterfaceRef ! LocalInterface.BetterNeighbourAppeared
      }
  }

  //object ids coming from other node
  private def processInv: Receive = {
    case DataFromPeer(spec, invData: InvData@unchecked, remote)
      if spec.messageCode == InvSpec.MessageCode =>

      viewHolderRef ! CompareViews(remote, invData._1, invData._2)
  }

  //other node asking for objects by their ids
  private def modifiersReq: Receive = {
    case DataFromPeer(spec, invData: InvData@unchecked, remote)
      if spec.messageCode == RequestModifierSpec.MessageCode =>

      viewHolderRef ! GetLocalObjects(remote, invData._1, invData._2)
  }

  //other node is sending objects
  private def modifiersFromRemote: Receive = {
    case DataFromPeer(spec, data: ModifiersData@unchecked, remote)
      if spec.messageCode == ModifiersSpec.messageCode =>

      val typeId = data._1
      val modifiers = data._2

      val askedIds = asked.getOrElse(typeId, mutable.Set())

      log.info(s"Got modifiers type $typeId with ids ${data._2.keySet.map(Base58.encode).mkString(",")}")
      log.info(s"Asked ids ${data._2.keySet.map(Base58.encode).mkString(",")}")

      val fm = modifiers.flatMap { case (mid, mod) =>
        if (askedIds.exists(id => id sameElements mid)) {
          askedIds.retain(id => !(id sameElements mid))
          Some(mod)
        } else {
          None
          //todo: remote peer has sent some object not requested -> ban?
        }
      }.toSeq

      asked.put(typeId, askedIds)
      val msg = ModifiersFromRemote(remote, data._1, fm)
      viewHolderRef ! msg
  }

  //local node sending object ids to remote
  private def requestFromLocal: Receive = {
    case RequestFromLocal(peer, modifierTypeId, modifierIds) =>

      if (modifierIds.nonEmpty) {
        val msg = Message(requestModifierSpec, Right(modifierTypeId -> modifierIds), None)
        peer.handlerRef ! msg
      }
      val newIds = asked.getOrElse(modifierTypeId, mutable.Set()) ++ modifierIds
      asked.put(modifierTypeId, newIds)
  }

  //local node sending out objects requested to remote
  private def responseFromLocal: Receive = {
    case ResponseFromLocal(peer, typeId, modifiers: Seq[NodeViewModifier]) =>
      if (modifiers.nonEmpty) {
        val modType = modifiers.head.modifierTypeId
        val m = modType -> modifiers.map(m => m.id -> m.bytes).toMap
        val msg = Message(ModifiersSpec, Right(m), None)
        peer.handlerRef ! msg
      }
  }

  override def receive: Receive =
    getLocalSyncInfo orElse
      syncSend orElse
      processSync orElse
      processSyncStatus orElse
      processInv orElse
      modifiersReq orElse
      requestFromLocal orElse
      responseFromLocal orElse
      modifiersFromRemote orElse
      viewHolderEvents orElse {
      case a: Any => log.error("Strange input: " + a)
    }
}

object NodeViewSynchronizer {

  case object GetLocalSyncInfo

  case class CompareViews(source: ConnectedPeer, modifierTypeId: ModifierTypeId, modifierIds: Seq[ModifierId])

  case class GetLocalObjects(source: ConnectedPeer, modifierTypeId: ModifierTypeId, modifierIds: Seq[ModifierId])

  case class RequestFromLocal(source: ConnectedPeer, modifierTypeId: ModifierTypeId, modifierIds: Seq[ModifierId])

  case class ResponseFromLocal[M <: NodeViewModifier](source: ConnectedPeer, modifierTypeId: ModifierTypeId, localObjects: Seq[M])

  case class ModifiersFromRemote(source: ConnectedPeer, modifierTypeId: ModifierTypeId, remoteObjects: Seq[Array[Byte]])

  case class OtherNodeSyncingInfo[SI <: SyncInfo](peer: ConnectedPeer, syncInfo: SI)

}