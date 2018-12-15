package me.wooy.proxy.client

import java.io.File

object GFWRegex {
  private var regexList:List<Regex> = listOf()
  fun doNeedProxy(host:String):Boolean =
    regexList.any {
      it.containsMatchIn(host)
    }
  fun initRegexList(path:String){
    regexList = File(path).readLines().map {
      it.toRegex()
    }
  }
}

