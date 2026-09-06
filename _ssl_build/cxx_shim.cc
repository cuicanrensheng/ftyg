// BoringSSL C++ 运行时垫片：-fno-exceptions/-fno-rtti 下剩余的 libc++ 依赖
// （libc++ 断言 abort 与 operator delete），直接映射 bionic libc，免去 libc++_shared 依赖。
#include <cstdlib>
namespace std {
inline namespace __ndk1 {
__attribute__((noreturn, cold)) void __libcpp_verbose_abort(const char *, ...) {
    std::abort();
}
}  // namespace __ndk1
}  // namespace std
void operator delete(void *p) noexcept { std::free(p); }
