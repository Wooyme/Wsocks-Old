#include "me_wooy_proxy_jni_Kcp.h"
#include <sys/socket.h>
#include <strings.h>
#include <netinet/in.h>
#include <linux/if_ether.h>
#include <pthread.h>
#include <string.h>
#include <zconf.h>
#include "udp.h"
#include "client.h"
JNIEXPORT jlong JNICALL Java_me_wooy_proxy_jni_Kcp_init
        (JNIEnv* env, jobject obj, jstring jfake_ip,jint fake_port, jint local_port, jstring jremote_ip, jint remote_port){
    const char* fake_ip = (*env)->GetStringUTFChars(env, jfake_ip, 0);
    const char* remote_ip = (*env)->GetStringUTFChars(env,jremote_ip,0);
    struct client_info* info = malloc(sizeof(struct client_info));
    int raw_sock = socket(AF_INET, SOCK_RAW, IPPROTO_RAW);
    info->raw_sock = raw_sock;
    info->fake_ip = fake_ip;
    info->fake_port = fake_port;
    info->local_port = local_port;
    info->remote_ip = remote_ip;
    info->remote_port = remote_port;
    return info;
}


JNIEXPORT void JNICALL Java_me_wooy_proxy_jni_Kcp_sendBuf(JNIEnv * env, jobject obj, jlong info, jbyteArray array,jint buf_len){
    char* buf = (*env)->GetByteArrayElements(env,array,NULL);
    struct client_info* client_info = (struct client_info*)info;
    int raw_sock = client_info->raw_sock;
    uint8_t packet[ETH_DATA_LEN];
    uint8_t udp_packet[ETH_DATA_LEN];
    char *source = client_info->fake_ip;
    unsigned int packet_size;
    struct sockaddr_in src_addr;
    struct sockaddr_in dst_addr;

    src_addr.sin_family = AF_INET;
    src_addr.sin_port = htons((uint16_t) client_info->fake_port);
    inet_aton(source, &src_addr.sin_addr);

    dst_addr.sin_family = AF_INET;
    dst_addr.sin_port = htons((uint16_t) client_info->remote_port);
    inet_aton(client_info->remote_ip, &dst_addr.sin_addr);

    packet_size = build_udp_packet(src_addr, dst_addr, udp_packet, buf, buf_len);

    build_ip_packet(src_addr.sin_addr, dst_addr.sin_addr, IPPROTO_UDP, packet, udp_packet, packet_size);

    // if((raw_sock = socket(AF_INET, SOCK_RAW, IPPROTO_RAW)) < 0){
    //     return -1;
    // }
    send_udp_packet(raw_sock, src_addr, dst_addr, buf, buf_len);
    (*env)->ReleaseByteArrayElements(env,array,buf,0);
}