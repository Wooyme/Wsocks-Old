package me.wooy.proxy.common

import io.vertx.core.json.JsonObject
import org.apache.commons.codec.digest.DigestUtils

class UserInfo(val username:String,val password:String,val key:ByteArray,val doZip:Boolean = false,val offset:Int = 0,val maxLoginDevices:Int=-1,val limitation:Long = -1L){
  fun secret():String{
    return DigestUtils.md5Hex((if(this.doZip) "1" else "0")+this.username+this.password)
  }
  companion object {
    fun fromJson(json:JsonObject):UserInfo{
      val array = json.getString("pass").toByteArray()
      val raw = if (16 > array.size)
        array + ByteArray(16 - array.size) { 0x06 }
      else
        array
      return UserInfo(json.getString("user")
          ,json.getString("pass")
          ,raw
          ,json.getBoolean("zip")?:false
          ,json.getInteger("offset")?:0
          ,json.getInteger("multiple")?:-1
          ,json.getLong("limit")?:-1L)
    }
  }
}
