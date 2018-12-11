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
 -T,--type <arg>           选择服务端或客户端[server/client>]
```
服务端
-----
**服务端需要架设在可访问墙外世界的服务器上**
```
运行
java -jar proxy.jar -T server -LP <端口号> -C <用户列表路径>
后台运行(Windows不可用)
nohup java -jar proxy.jar -T server -LP <端口号> -C <用户列表路径> &
```
用户列表格式
```
{"users":{"user1":"passwd1","user2":"passwd2",........}}
```
*服务端默认监听1888端口,请确保该端口没有被占用*

客户端
-----
```
运行
java -jar proxy.jar -T client -RI <服务器IP> -RP <服务器端口> -LP <本地端口> -U <用户名> -P <密码>
后台运行(Linux\Unix Only)
nohup java -jar proxy.jar -T client -RI <服务器IP> -RP <服务器端口> -LP <本地端口> -U <用户名> -P <密码> &
后台运行(Windows Only)
START /B java -jar proxy.jar -T client -RI <服务器IP> -RP <服务器端口> -LP <本地端口> -U <用户名> -P <密码>
```
客户端运行后会在本地监听2888端口，使用HTTP协议  
开启客户端之后需要设置浏览器代理
* Firefox  
  https://support.mozilla.org/en-US/kb/connection-settings-firefox
* Chrome on Ubuntu  
  https://www.expressvpn.com/support/troubleshooting/google-chrome-no-proxy/

Help
====

* https://vertx.io/docs/[Vert.x Documentation]
* https://stackoverflow.com/questions/tagged/vert.x?sort=newest&pageSize=15[Vert.x Stack Overflow]
* https://groups.google.com/forum/?fromgroups#!forum/vertx[Vert.x User Group]
* https://gitter.im/eclipse-vertx/vertx-users[Vert.x Gitter]


