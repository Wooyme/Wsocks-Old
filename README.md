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
服务端
-----
**服务端需要架设在可访问墙外世界的服务器上**
```
运行
java -jar proxy.jar server
后台运行(Windows不可用)
nohup java -jar proxy.jar server &
```
*服务端默认监听1888端口,请确保该端口没有被占用*

客户端
-----
```
运行
java -jar proxy.jar client <服务器IP>
后台运行(Windows不可用)
nohup java -jar proxy.jar client <服务器IP> &
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


