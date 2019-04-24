package me.wooy.proxy.jni;
public class Kcp{
    static {
//        System.loadLibrary("KcpTest");
        System.loadLibrary("RawTcp");
    }
    public native long init(String fakeIp,int fakePort,int localPort,String remoteIp,int remotePort);
    public native void sendBuf(long info,byte[] buf,int len);

}