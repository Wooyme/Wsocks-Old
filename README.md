LightSocks
==========
一个基于Vertx实现的类Shadowsocks代理工具

Building
========

To launch your tests:
```
./mvnw clean test
```

To package your application:
```
./mvnw clean package
```

To run your application:
```
./mvnw clean exec:java
```

Usage
=====
**程序运行需要Java8环境**
* Linux\Unix用户  
  https://openjdk.java.net/install/
* Windows用户  
  https://www.java.com/zh_CN/download/

****
命令行说明
----
```
usage: utility-name
 -C,--config-user <arg>    用户列表路径
 -LP,--local-port <arg>    客户端或服务端的本地端口
 -P,--pass <arg>           用户密码
 -U,--user <arg>           用户名
 -RI,--remote-ip <arg>     远程服务器IP
 -RP,--remote-port <arg>   远程服务器端口
 -T,--type <arg>           选择服务端或客户端[server/client-http/client-http-ui/client-socks5/client-socks5-ui]
```
服务端
-----
**服务端需要架设在可访问墙外世界的服务器上**
**服务器需要安装Java8，Oracle或是OpenJDK（只要不是2年前的版本）都可以**
```
运行(Linux\Unix Only)
nohup java -jar proxy.jar -T server -LP <端口号> -C <用户列表路径> &
nohup java -jar \
-Djava.net.preferIPv4Stack=true \
-Dvertx.disableDnsResolver=true \
-Dio.netty.leakDetection.level=paranoid \
-XX:MaxDirectMemorySize=128m \
-Djdk.nio.maxChachedBufferSize=262144 \
proxy.jar -T server -LP 1888 -C config.json &
```
用户列表格式
```
{"users":{"user1":"passwd1","user2":"passwd2",........}}
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
**客户端HTTP代理部分不再更新**  
**如果有必须要用HTTP代理的情况，可以运行无GUI版本(client-http)**

*Linux*  
```
chmod a+x wsocks.AppImage
```  
>然后运行wsocks.AppImage即可  

*Windows*  
>解压后运行socks5-client.bat即可

*命令行运行*
```
后台运行(Linux\Unix Only)
nohup java -jar proxy.jar -T client -RI <服务器IP> -RP <服务器端口> -LP <本地端口> -U <用户名> -P <密码> &
后台运行(Windows Only)
javaw -jar proxy.jar -T client -RI <服务器IP> -RP <服务器端口> -LP <本地端口> -U <用户名> -P <密码>
后台运行GUI模式(Linux\Unix Only)
nohup java -jar proxy.jar -T <client-socks5-ui> &
后台运行GUI模式(Windows Only)
javaw -jar proxy.jar -T <client-socks5-ui>
```

开启客户端之后需要设置浏览器代理
* Firefox  
  https://support.mozilla.org/en-US/kb/connection-settings-firefox
* Chrome on Ubuntu  
  https://www.expressvpn.com/support/troubleshooting/google-chrome-no-proxy/  
  或  
  安装Proxy Switcher and Manager(推荐)

Help
====

* https://vertx.io/docs/[Vert.x Documentation]
* https://stackoverflow.com/questions/tagged/vert.x?sort=newest&pageSize=15[Vert.x Stack Overflow]
* https://groups.google.com/forum/?fromgroups#!forum/vertx[Vert.x User Group]
* https://gitter.im/eclipse-vertx/vertx-users[Vert.x Gitter]


