package me.wooy.proxy.server

import io.vertx.core.Vertx

fun main(){
  val vertx = Vertx.vertx()
  vertx.fileSystem().createTempFile("a",""){
    if(it.failed()) it.cause().printStackTrace()
    else
      println(it.result())
  }
}