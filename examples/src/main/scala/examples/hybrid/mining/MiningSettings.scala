package examples.hybrid.mining

import io.circe.syntax._
import scorex.core.settings.Settings

import scala.concurrent.duration._

trait MiningSettings extends Settings with MiningConstants {
  lazy val BlockDelay: Long = if (isTestnet) 10.minutes.toMillis
  else 10.seconds.toMillis

  lazy val offlineGeneration = settingsJSON.get("offlineGeneration").flatMap(_.asBoolean).getOrElse(false)

  lazy val posAttachmentSize = settingsJSON.get("posAttachmentSize").flatMap(_.asNumber).flatMap(_.toInt)
    .getOrElse(DefaulPtosAttachmentSize)

  lazy val twinsR = settingsJSON.get("twinsR").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(8)

  val DefaulPtosAttachmentSize = 1024

  override def toString: String = (Map("BlockDelay" -> BlockDelay.asJson) ++
    settingsJSON.map(s => s._1 -> s._2)).asJson.spaces2
}
