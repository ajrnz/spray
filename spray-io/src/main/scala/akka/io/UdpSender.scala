/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.nio.channels.DatagramChannel
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.io.Inet.SocketOption
import akka.io.Udp._
import akka.actor._

/**
 * INTERNAL API
 */
private[io] class UdpSender(val udp: UdpExt,
                            channelRegistry: ChannelRegistry,
                            options: immutable.Traversable[SocketOption],
                            commander: ActorRef)
    extends Actor with ActorLogging with WithUdpSend {

  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    val socket = datagramChannel.socket

    options foreach { _.beforeDatagramBind(socket) }

    datagramChannel
  }
  channelRegistry.register(channel, initialOps = 0)

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      context.become(sendHandlers(registration), discardOld = true)
      commander ! SimpleSendReady
  }

  override def postStop(): Unit = if (channel.isOpen) {
    log.debug("Closing DatagramChannel after being stopped")
    try channel.close()
    catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing DatagramChannel")
    }
  }
}

