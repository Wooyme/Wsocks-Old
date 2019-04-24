package me.wooy.proxy

import io.vertx.core.Vertx
import org.pcap4j.core.BpfProgram
import org.pcap4j.core.PacketListener
import org.pcap4j.core.PcapNetworkInterface
import org.pcap4j.packet.TcpPacket
import org.pcap4j.util.NifSelector

fun main(){
  val device = NifSelector().selectNetworkInterface()
  println(device)
  Vertx.vertx().createNetClient().connect(7777,"104.223.56.29"){
    if(it.failed()) {
      it.cause().printStackTrace()
      System.exit(-1)
    }
    println("Connected to remote")
    it.result().write("Hello")
  }
  Thread {
    pcapLoop(device)
  }.start()
}

fun pcapLoop(device:PcapNetworkInterface){
  val snapshotLength = 65536
  val readTimeout = 50
  val handle = device.openLive(snapshotLength,PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,readTimeout)
  handle.setFilter("tcp src port 7777",BpfProgram.BpfCompileMode.OPTIMIZE)
  handle.loop(-1, PacketListener {
    val tcp = it.get(TcpPacket::class.java)
    println(String(tcp.payload.rawData))
  })
}