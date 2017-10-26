package scorex.core.transaction

import scorex.core.{ModifierId, NodeViewComponent}

import scala.util.Try

/**
  * Unconfirmed transactions pool
  *
  * @tparam TX -type of transaction the pool contains
  */
trait MemoryPool[TX <: Transaction[_], M <: MemoryPool[TX, M]] extends NodeViewComponent {

  //getters
  def getById(id: ModifierId): Option[TX]

  def contains(id: ModifierId): Boolean

  //get ids from Seq, not presenting in mempool
  def notIn(ids: Seq[ModifierId]): Seq[ModifierId] = ids.filter(id => !contains(id))

  def getAll(ids: Seq[ModifierId]): Seq[TX]

  /**
    * Method to put a transaction into the memory pool. Validation of tha transactions against
    * the state is done in NodeVieHolder. This put() method can check whether a transaction is valid
    * @param tx
    * @return Success(updatedPool), if transaction successfully added to the pool, Failure(_) otherwise
    */
  def put(tx: TX): Try[M]

  def put(txs: Iterable[TX]): Try[M]

  def putWithoutCheck(txs: Iterable[TX]): M

  def remove(tx: TX): M

  def take(limit: Int): Iterable[TX]

  def filter(txs: Seq[TX]): M = filter(t => !txs.exists(_.id sameElements t.id))

  def filter(condition: TX => Boolean): M

  def size: Int
}