package pl.touk.nussknacker.engine.kafka

import java.net.ServerSocket

object AvailablePortFinder {

  def findAvailablePort(): Int = {
    val socket = new ServerSocket(0)
    try {
      socket.setReuseAddress(true)
      socket.getLocalPort
    } finally {
      socket.close()
    }
  }

}
