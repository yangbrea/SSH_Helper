#include "ssh/ssh_libssh2.h"

#include <cassert>
#include <string>

int main() {
    using namespace sshnative;

    const std::string version = Libssh2Session::libraryVersion();
    assert(!version.empty());
    assert(version.find("libssh2_") != std::string::npos || version.find("1.") != std::string::npos);

    {
        Libssh2Session session;
        assert(session.get() != nullptr);
    }
    {
        Libssh2Session session;
        assert(session.get() != nullptr);
    }
}
