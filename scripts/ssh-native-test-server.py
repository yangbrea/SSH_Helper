#!/usr/bin/env python3
"""AsyncSSH test server used by ssh-native E2E host tests.

Supports password auth and a single exec command that prints
"native-exec-ok" and exits 0. Prints the listening port on stdout.
"""
import asyncio
import asyncssh
import sys


class ExecSession(asyncssh.SSHServerSession):
    def connection_made(self, chan):
        self._chan = chan

    def exec_requested(self, command):
        return True

    def session_started(self):
        self._chan.write("native-exec-ok")
        self._chan.exit(0)


class Server(asyncssh.SSHServer):
    def begin_auth(self, username):
        return True

    def password_auth_supported(self):
        return True

    def validate_password(self, username, password):
        return username == "test" and password == "secret"

    def public_key_auth_supported(self):
        return True

    def validate_public_key(self, username, key):
        return username == "test"

    def session_requested(self):
        return ExecSession()


async def main():
    key = asyncssh.generate_private_key("ssh-ed25519")
    server = await asyncssh.create_server(
        Server,
        "127.0.0.1",
        0,
        server_host_keys=[key],
    )
    port = server.sockets[0].getsockname()[1]
    print(port, flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
