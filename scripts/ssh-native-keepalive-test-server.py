#!/usr/bin/env python3
"""AsyncSSH server for native keepalive E2E.

Optional argv[1] is a "die_after" delay in seconds; after authentication the
server closes the connection, simulating a remote transport close.
"""
import asyncio
import asyncssh
import sys


class Server(asyncssh.SSHServer):
    def __init__(self, die_after=None):
        self._die_after = die_after

    def begin_auth(self, username):
        return True

    def password_auth_supported(self):
        return True

    def validate_password(self, username, password):
        return username == "test" and password == "secret"

    def auth_completed(self):
        if self._die_after is not None:
            asyncio.get_event_loop().call_later(self._die_after, self.close_connection)

    def close_connection(self):
        # Keep a handle to the current connection through session_requested.
        pass


class Session(asyncssh.SSHServerSession):
    def connection_made(self, chan):
        self._chan = chan

    def session_requested(self):
        return True

    def exec_requested(self, command):
        return True

    def pty_requested(self, term_type, term_size, term_modes):
        return True

    def shell_requested(self):
        return True

    def data_received(self, data, datatype):
        self._chan.write(data)

    def session_started(self):
        pass


async def main():
    die_after = float(sys.argv[1]) if len(sys.argv) > 1 else None
    server_state = {}

    class BoundServer(Server):
        def session_requested(self):
            return Session()

        def connection_made(self, conn):
            super().connection_made(conn)
            server_state["conn"] = conn

        def close_connection(self):
            conn = server_state.get("conn")
            if conn is not None:
                conn.close()

    key = asyncssh.generate_private_key("ssh-ed25519")
    srv = await asyncssh.create_server(
        lambda: BoundServer(die_after),
        "127.0.0.1", 0,
        server_host_keys=[key],
    )
    print(srv.sockets[0].getsockname()[1], flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
