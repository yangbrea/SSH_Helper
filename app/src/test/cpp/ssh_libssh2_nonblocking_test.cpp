#include "ssh/ssh_libssh2_nonblocking.h"

#include <libssh2.h>

#include <cassert>

int main() {
    assert(libssh2_init(0) == 0);
    LIBSSH2_SESSION* session = libssh2_session_init();
    assert(session != nullptr);
    libssh2_session_set_blocking(session, 0);

    const auto success = sshnative::classifyLibssh2Int(
        session, 7, 0, sshnative::ErrorDomain::kSshHandshake, "handshake");
    assert(success.kind == sshnative::Libssh2CallKind::kSucceeded);

    const auto count = sshnative::classifyLibssh2Count(
        session, 7, 12, sshnative::ErrorDomain::kChannel, "channel_read");
    assert(count.kind == sshnative::Libssh2CallKind::kSucceeded && count.value == 12);

    const auto pointer = sshnative::classifyLibssh2Pointer(
        session, 7, session, sshnative::ErrorDomain::kChannel, "channel_open");
    assert(pointer.kind == sshnative::Libssh2CallKind::kSucceeded);
    assert(pointer.pointer == session);

    const auto would_block = sshnative::classifyLibssh2Int(
        session, 7, LIBSSH2_ERROR_EAGAIN, sshnative::ErrorDomain::kAuth, "password_auth");
    assert(would_block.kind == sshnative::Libssh2CallKind::kWouldBlock);

    const auto failure = sshnative::classifyLibssh2Int(
        session, 7, LIBSSH2_ERROR_INVAL, sshnative::ErrorDomain::kAuth, "password_auth");
    assert(failure.kind == sshnative::Libssh2CallKind::kFailed);
    assert(failure.error.domain == sshnative::ErrorDomain::kAuth);
    assert(failure.error.code == "password_auth_failed");

    assert(libssh2_session_free(session) == 0);
    libssh2_exit();
}
