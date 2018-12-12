package me.wooy.proxy.client

import io.vertx.core.Vertx

fun main(){
  val vertx = Vertx.vertx()
  vertx.deployVerticle(ClientSocks5())
}
