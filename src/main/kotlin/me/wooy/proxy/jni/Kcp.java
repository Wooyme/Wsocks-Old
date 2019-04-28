package me.wooy.proxy.jni;
public class Kcp{
    static {
        System.loadLibrary("RawTcp");
    }
    public native void initSock();
    public native long init(String fakeIp,int fakePort,int localPort,String remoteIp,int remotePort);
    public native void sendRaw(long info,byte[] buf,int len);

}