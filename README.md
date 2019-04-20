Wsocks
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
 -C,--config-user <arg>    配置文件路径
 -LP,--local-port <arg>    客户端的本地端口
 -P,--pass <arg>           用户密码
 -U,--user <arg>           用户名
 -RI,--remote-ip <arg>     远程服务器IP
 -RP,--remote-port <arg>   远程服务器端口
 -T,--type <arg>           选择服务端或客户端[server/client-socks5/client-socks5-ui]
 -K,--key <arg>            设置秘钥
 -O,--offset <arg>         设置数据偏移
```
服务端
-----
**服务端需要架设在可访问墙外世界的服务器上**
**服务器需要安装Java8，Oracle或是OpenJDK（只要不是2年前的版本）都可以**
```
运行(Linux\Unix Only)
nohup java -jar proxy.jar -T server -LP <端口号> -C <配置文件路径> &
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
{"port":端口号,"key":"Aes加密秘钥，可以填写9位-16位的任意字符串"，"offset":数据偏移量(可以填写任意整数不要太大),"users":{"user1":"passwd1","user2":"passwd2",........}}
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
nohup java -jar proxy.jar -T client -RI <服务器IP> -RP <服务器端口> -LP <本地端口> -U <用户名> -P <密码> -K <秘钥> -O <数据偏移> &
后台运行(Windows Only)
START javaw -jar proxy.jar -T client -RI <服务器IP> -RP <服务器端口> -LP <本地端口> -U <用户名> -P <密码> -K <秘钥> -O <数据偏移>
后台运行GUI模式(Linux\Unix Only)
nohup java -jar proxy.jar -T <client-socks5-ui> &
后台运行GUI模式(Windows Only)
javaw -jar proxy.jar -T <client-socks5-ui>
```

*开启客户端之后需要设置浏览器代理*
* Firefox  
  https://support.mozilla.org/en-US/kb/connection-settings-firefox
* Chrome on Ubuntu  
  https://www.expressvpn.com/support/troubleshooting/google-chrome-no-proxy/  
  或  
  安装Proxy Switcher and Manager(推荐)

****
*关于GFW List*  
浏览器使用gfw list一般可以使用PAC，Chrome的Proxy Switcher and Manager和Firefox的一些插件都是支持PAC的。对于那些不支持PAC的情景，Wsocks客户端内置了gfw list支持。只需要在`Edit Local`中配置list文件目录并开启`Use GFW List`即可。  
AppImage打包内置一个gfw.lst，会在第一次运行的时候释放到`$HOME/.wsocks`目录。Windows用户需要自行复制压缩包中的gfw.lst到`用户目录/.wsocks`目录


Help
====

* https://vertx.io/docs/[Vert.x Documentation]
* https://stackoverflow.com/questions/tagged/vert.x?sort=newest&pageSize=15[Vert.x Stack Overflow]
* https://groups.google.com/forum/?fromgroups#!forum/vertx[Vert.x User Group]
* https://gitter.im/eclipse-vertx/vertx-users[Vert.x Gitter]


