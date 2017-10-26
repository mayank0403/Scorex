package examples.hybrid.mining

import akka.actor.{Actor, ActorRef}
import examples.commons.SimpleBoxTransactionMemPool
import examples.hybrid.blocks.{HybridBlock, PowBlock, PowBlockCompanion, PowBlockHeader}
import examples.hybrid.history.HybridHistory
import examples.hybrid.state.HBoxStoredState
import examples.hybrid.util.Cancellable
import examples.hybrid.wallet.HWallet
import scorex.core.LocalInterface.LocallyGeneratedModifier
import scorex.core.ModifierId
import scorex.core.NodeViewHolder.{CurrentView, GetDataFromCurrentView}
import scorex.core.block.Block.BlockId
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Blake2b256

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Random

/**
  * A controller for PoW mining
  * currently it is starting to work on getting a (PoW; PoS) block references
  * and stops on a new PoW block found (when PoS ref is unknown)
  */
class PowMiner(viewHolderRef: ActorRef, settings: HybridMiningSettings) extends Actor with ScorexLogging {

  import PowMiner._

  private var cancellableOpt: Option[Cancellable] = None
  private var mining = false
  private val getRequiredData: GetDataFromCurrentView[HybridHistory,
    HBoxStoredState,
    HWallet,
    SimpleBoxTransactionMemPool,
    PowMiningInfo] = {
    val f: CurrentView[HybridHistory, HBoxStoredState, HWallet, SimpleBoxTransactionMemPool] => PowMiningInfo = {
      view: CurrentView[HybridHistory, HBoxStoredState, HWallet, SimpleBoxTransactionMemPool] =>

        val difficulty = view.history.powDifficulty
        val pairCompleted = view.history.pairCompleted
        val bestPowBlock = view.history.bestPowBlock
        val bestPosId = view.history.bestPosId
        val pubkey = if (view.vault.publicKeys.nonEmpty) {
          view.vault.publicKeys.head
        } else {
          view.vault.generateNewSecret().publicKeys.head
        }
        PowMiningInfo(pairCompleted, difficulty, bestPowBlock, bestPosId, pubkey)
    }
    GetDataFromCurrentView[HybridHistory,
      HBoxStoredState,
      HWallet,
      SimpleBoxTransactionMemPool,
      PowMiningInfo](f)
  }


  override def preStart(): Unit = {
    //todo: check for a last block (for what?)
    if (settings.offlineGeneration) {
      context.system.scheduler.scheduleOnce(1.second)(self ! StartMining)
    }
  }

  override def receive: Receive = {
    case StartMining =>
      if (settings.blockGenerationDelay >= 1.minute) {
        log.info("Mining is disabled for blockGenerationDelay >= 1 minute")
      } else {
        mining = true
        self ! MineBlock
      }

    case MineBlock =>
      if (mining) {
        log.info("Mining of previous PoW block stopped")
        cancellableOpt.forall(_.cancel())

        context.system.scheduler.scheduleOnce(50.millis) {
          if (cancellableOpt.forall(_.status.isCancelled)) viewHolderRef ! getRequiredData
          else self ! StartMining
        }
      }

    case pmi: PowMiningInfo =>

      if (!cancellableOpt.forall(_.status.isCancelled)) {
        log.warn("Trying to run miner when the old one is still running")
      } else {
        val difficulty = pmi.powDifficulty
        val bestPowBlock = pmi.bestPowBlock

        val (parentId, prevPosId, brothers) = if (!pmi.pairCompleted) {
          //brother
          log.info(s"Starting brother mining for ${Base58.encode(bestPowBlock.parentId)}:${Base58.encode(bestPowBlock.prevPosId)}")
          val bs = bestPowBlock.brothers :+ bestPowBlock.header
          (bestPowBlock.parentId, bestPowBlock.prevPosId, bs)
        } else {
          log.info(s"Starting new block mining for ${bestPowBlock.encodedId}:${Base58.encode(pmi.bestPosId)}")
          (bestPowBlock.id, pmi.bestPosId, Seq()) //new step
        }
        val pubkey = pmi.pubkey

        val p = Promise[Option[PowBlock]]()
        cancellableOpt = Some(Cancellable.run() { status =>
          Future {
            var foundBlock: Option[PowBlock] = None
            var attemps = 0

            while (status.nonCancelled && foundBlock.isEmpty) {
              foundBlock = powIteration(parentId, prevPosId, brothers, difficulty, settings, pubkey, settings.blockGenerationDelay)
              attemps = attemps + 1
              if (attemps % 100 == 99) {
                log.debug(s"100 hashes tried, difficulty is $difficulty")
              }
            }
            p.success(foundBlock)
          }
        })

        p.future.onComplete { toBlock =>
          toBlock.getOrElse(None).foreach { block =>
            log.debug(s"Locally generated PoW block: $block with difficulty $difficulty")
            self ! block
          }
        }
      }


    case b: PowBlock =>
      cancellableOpt.foreach(_.cancel())
      viewHolderRef ! LocallyGeneratedModifier[HybridBlock](b)

    case StopMining =>
      mining = false

    case a: Any =>
      log.warn(s"Strange input: $a")
  }
}

object PowMiner extends App {

  case object StartMining

  case object StopMining

  case object MineBlock

  case class PowMiningInfo(pairCompleted: Boolean,
                           powDifficulty: BigInt,
                           bestPowBlock: PowBlock,
                           bestPosId: ModifierId,
                           pubkey: PublicKey25519Proposition)

  def powIteration(parentId: BlockId,
                   prevPosId: BlockId,
                   brothers: Seq[PowBlockHeader],
                   difficulty: BigInt,
                   settings: HybridMiningSettings,
                   proposition: PublicKey25519Proposition,
                   blockGenerationDelay: FiniteDuration
                  ): Option[PowBlock] = {
    val nonce = Random.nextLong()

    val ts = System.currentTimeMillis()

    val bHash = if (brothers.isEmpty) Array.fill(32)(0: Byte) else Blake2b256(PowBlockCompanion.brotherBytes(brothers))

    val b = PowBlock(parentId, prevPosId, ts, nonce, brothers.size, bHash, proposition, brothers)

    val foundBlock =
      if (b.correctWork(difficulty, settings)) {
        Some(b)
      } else {
        None
      }
    Thread.sleep(blockGenerationDelay.toMillis)
    foundBlock
  }

}