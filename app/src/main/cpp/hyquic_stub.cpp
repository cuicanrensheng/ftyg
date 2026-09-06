#include <stddef.h>
#include <stdint.h>

#if defined(__aarch64__) || defined(__x86_64__)

typedef unsigned int  quic_addrlen_t;
typedef unsigned long quic_len_t;
#else

typedef int           quic_addrlen_t;
typedef unsigned int  quic_len_t;
#endif

struct epoll_event {
    uint32_t events;
    uint64_t data;
};

struct sockaddr {
    uint16_t sa_family;
    char sa_data[14];
};

#define STUB_EXPORT __attribute__((visibility("default")))

namespace posix_quic {

STUB_EXPORT int GetCategory(int err) { return 0; }
STUB_EXPORT int QuicConnect(int sock, const sockaddr* addr, quic_addrlen_t addrlen) { return -1; }
STUB_EXPORT int GetQuicError(int sock, int* a, int* b, int* c, int* d) { return -1; }
STUB_EXPORT int QuicEpollCtl(int epfd, int op, int fd, epoll_event* event) { return -1; }
STUB_EXPORT int QuicEpollWait(int epfd, epoll_event* events, int maxevents, int timeout) { return -1; }

STUB_EXPORT void QuicSetLogFunc(void (*log_func)(const char*, int, const char*, const char*), uint64_t flag) {}
STUB_EXPORT int QuicCloseSocket(int sock) { return 0; }
STUB_EXPORT int QuicCloseStream(int stream) { return 0; }
STUB_EXPORT int QuicCreateEpoll() { return -1; }
STUB_EXPORT int QuicEpollNotify(int epfd) { return -1; }
STUB_EXPORT int QuicCloseEpoller(int epfd) { return 0; }
STUB_EXPORT int QuicCreateSocket() { return -1; }
STUB_EXPORT int QuicCreateStream(int sock) { return -1; }
STUB_EXPORT int QuicStreamAccept(int stream) { return -1; }
STUB_EXPORT int QuicRead(int stream, void* buf, quic_len_t len) { return -1; }
STUB_EXPORT int QuicWrite(int stream, const void* buf, quic_len_t len, bool fin) { return -1; }

}
