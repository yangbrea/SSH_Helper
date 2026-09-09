#!/usr/bin/env python3
"""Simple local HTTP CONNECT / SOCKS5 forwarding proxy for native SSH E2E tests.

Usage: ssh-native-test-proxy.py http|socks5 <ssh_port>
Prints the listening proxy port on stdout and tunnels to 127.0.0.1:<ssh_port>.
"""
import asyncio
import socket
import sys


async def pipe(reader, writer):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def relay(client_reader, client_writer, ssh_port):
    try:
        server_reader, server_writer = await asyncio.open_connection(
            "127.0.0.1", ssh_port
        )
    except Exception:
        client_writer.close()
        return
    await asyncio.gather(
        pipe(client_reader, server_writer),
        pipe(server_reader, client_writer),
    )


async def handle_http(client_reader, client_writer, ssh_port):
    request = bytearray()
    while b"\r\n\r\n" not in request:
        chunk = await client_reader.read(4096)
        if not chunk:
            client_writer.close()
            return
        request.extend(chunk)
        if len(request) > 65536:
            client_writer.close()
            return
    try:
        line = bytes(request).split(b"\r\n", 1)[0].decode("ascii")
        parts = line.split()
        if len(parts) < 2 or parts[0] != "CONNECT":
            raise ValueError("not CONNECT")
        host, port = parts[1].rsplit(":", 1)
        port = int(port)
        assert host == "127.0.0.1" and port == ssh_port
    except Exception:
        client_writer.write(b"HTTP/1.1 400 Bad Request\r\n\r\n")
        await client_writer.drain()
        client_writer.close()
        return
    client_writer.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
    await client_writer.drain()
    await relay(client_reader, client_writer, ssh_port)


async def read_exact(reader, size):
    data = b""
    while len(data) < size:
        chunk = await reader.read(size - len(data))
        if not chunk:
            raise ConnectionError("proxy read failed")
        data += chunk
    return data


async def handle_socks5(client_reader, client_writer, ssh_port):
    try:
        greeting = await read_exact(client_reader, 2)
        if greeting[0] != 0x05:
            raise ValueError("bad socks version")
        methods = await read_exact(client_reader, greeting[1])
        if 0x00 not in methods:
            client_writer.write(b"\x05\xff")
            await client_writer.drain()
            client_writer.close()
            return
        client_writer.write(b"\x05\x00")
        await client_writer.drain()

        header = await read_exact(client_reader, 4)
        if header[0] != 0x05 or header[1] != 0x01:
            raise ValueError("unsupported socks command")
        if header[3] == 0x01:
            host = socket.inet_ntop(socket.AF_INET, await read_exact(client_reader, 4))
        elif header[3] == 0x04:
            host = socket.inet_ntop(socket.AF_INET6, await read_exact(client_reader, 16))
        elif header[3] == 0x03:
            length = (await read_exact(client_reader, 1))[0]
            host = (await read_exact(client_reader, length)).decode()
        else:
            raise ValueError("bad atyp")
        port_bytes = await read_exact(client_reader, 2)
        port = (port_bytes[0] << 8) | port_bytes[1]
        assert host == "127.0.0.1" and port == ssh_port
        client_writer.write(b"\x05\x00\x00\x01\x7f\x00\x00\x01" + port_bytes)
        await client_writer.drain()
    except Exception:
        client_writer.close()
        return
    await relay(client_reader, client_writer, ssh_port)


async def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: ssh-native-test-proxy.py http|socks5 <ssh_port>")
    proxy_type = sys.argv[1]
    ssh_port = int(sys.argv[2])
    if proxy_type not in ("http", "socks5"):
        raise SystemExit("unknown proxy type: " + proxy_type)

    async def handle(reader, writer):
        if proxy_type == "http":
            await handle_http(reader, writer, ssh_port)
        else:
            await handle_socks5(reader, writer, ssh_port)

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    print(port, flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
