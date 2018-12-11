package me.wooy.proxy.client

import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import me.wooy.proxy.encryption.Aes

class Client:AbstractVerticle(){
  enum class Tag(val value:Short){
    CONNECT(0),DATA(1),FAIL(2),SUCCESS(3),CLOSE(4)
  }
  lateinit var server:HttpServer
  lateinit var client:NetClient
  init{
    server.connectionHandler { httpConnection->
      println("Take a connection from ${httpConnection.remoteAddress()}")
      httpConnection.closeHandler {
        println("Connection closed")
      }
    }
    server.requestHandler { req->
      if(req.method()== HttpMethod.CONNECT) {
        client.connect(PORT, IP) {
          if (it.failed()) println(it.cause())
          else {
            it.result().handler { buf ->
              //println("HashCode:${buf.hashCode()},Length:${buf.length()}")
              val tag = buf.getShort(0)
              println("Tag:$tag")
              if (tag != Tag.DATA.value) {
                if (tag == Tag.SUCCESS.value) {
                  req.netSocket().write("HTTP/1.1 200 Connection established\r\n\r\n")
                } else {
                  req.netSocket().write("HTTP/1.1 500 failed\r\n\r\n").end()
                  it.result().close()
                }
              } else {
                req.netSocket().write(Buffer.buffer(Aes.decrypt(buf.getBytes(2,buf.length()))))
              }
            }.closeHandler { _->
              req.netSocket().close()
            }
            it.result().send(Tag.CONNECT.value, Buffer.buffer(req.path()))
            req.netSocket().handler { buf->
              it.result().send(Tag.DATA.value,buf)
            }
            req.netSocket().closeHandler { _->
              it.result().close()
            }
          }
        }
      }else{
        val httpStrBuilder=StringBuilder()
        httpStrBuilder.append(req.method()).append(" ").append(req.path()).append(" HTTP/1.1\r\n")
        req.headers().forEach {
          httpStrBuilder.append(it.key).append(": ").append(it.value).append("\r\n")
        }
        req.bodyHandler { body->
          httpStrBuilder.append("\r\n").append(body).append("\r\n\r\n")
          client.connect(PORT, IP){
            if(it.failed()) println(it.cause())
            else{
              it.result().handler { buf->
                //val data=Buffer.buffer(aes.decrypt(aesPass, buf.getBytes(2, buf.length())))
                val data=Buffer.buffer(Aes.decrypt(buf.getBytes(2,buf.length())))
                when(buf.getShort(0)){
                  Tag.SUCCESS.value->{
                    it.result().send(Tag.DATA.value, Buffer.buffer(httpStrBuilder.toString()))
                  }
                  Tag.FAIL.value->{
                    req.netSocket().write("HTTP/1.1 500 failed\r\n\r\n").end()
                    it.result().close()
                  }
                  Tag.DATA.value->{
                    req.netSocket().write(data)
                  }
                }
              }
              it.result().send(Tag.CONNECT.value, Buffer.buffer("${req.host()}:80"))
            }
          }
        }
      }
      println("Method:"+req.rawMethod()+",Path:"+req.path())
    }

    server.listen(8080){
      if(it.succeeded()){
        println("Success")
      }else{
        println("Failed,${it.cause()}")
      }
    }
  }

  override fun start() {
    server = vertx.createHttpServer()

    super.start()
  }

  private fun NetSocket.send(tag:Short, buf:Buffer?=null){
    if(buf!=null){
      //this.write(Buffer.buffer().appendShort(tag).appendBytes(aes.encrypt(aesPass,buf.bytes)))
      this.write(Buffer.buffer().appendShort(tag).appendBytes(Aes.encrypt(buf.bytes)))
    }else{
      this.write(Buffer.buffer().appendShort(tag))
    }
  }

  companion object {
    const val PORT = 1889
    const val IP = "127.0.0.1"
  }
}
