Wsocks
==========
一个基于Vertx实现的类Shadowsocks代理工具


Usage
=====
**程序运行需要Java8环境**

****

服务端
-----
**服务端需要架设在可访问墙外世界的服务器上**
```
运行(Linux\Unix Only)
nohup java -jar \
-Djava.net.preferIPv4Stack=true \
-Dvertx.disableDnsResolver=true \
-Dio.netty.leakDetection.level=paranoid \
-XX:MaxDirectMemorySize=128m \
-Djdk.nio.maxChachedBufferSize=262144 \
proxy.jar -T server -C config.json &
```
配置文件格式
```
{"port":端口号,"dns":默认8.8.8.8,"users":[
    {
        "user":"用户名",
        "pass":"密码",
        "zip":是否使用gzip压缩,
        "multiple":是否可以多设备同时登录,
        "offset":数据偏移
        "limit":流量限制(尚未支持)
    }
]}
```
*解释一下各行参数:*
```
-Djava.net.preferIPv4Stack=true 只使用IPv4,因为经常可以看到试图访问google的IPV6地址的情况
-Dvertx.disableDnsResolver=true 禁用vertx自己的DNS解析，使用JVM默认的。因为vertx的经常出bug(汗
-Dio.netty.leakDetection.level=paranoid 一定程度上避免netty本身的内存泄露
-XX:MaxDirectMemorySize=128m 设置最大直接内存，可以根据服务器情况设置，如果服务端运行中出现 failed to allocate xxx bytes of direct memory的情况就要考虑增加这个值。
-Djdk.nio.maxChachedBufferSize=262144 设置nio的最大缓存，大多数情况下能够避免上述问题,如果服务器内存比较小，可以把这个值减小
```

客户端
-----

*Linux*  
```
java -jar proxy.jar
```

*Windows*  
>直接下载WSocks-1.0.exe安装包即可


*开启客户端之后需要设置浏览器代理*
* Firefox  
  https://support.mozilla.org/en-US/kb/connection-settings-firefox
* Chrome on Ubuntu  
  https://www.expressvpn.com/support/troubleshooting/google-chrome-no-proxy/  
  或  
  安装Proxy Switcher and Manager(推荐)


