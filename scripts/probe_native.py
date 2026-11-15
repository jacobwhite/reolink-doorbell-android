#!/usr/bin/env python3
"""Independent client check. pip install reolink-aio; captures only with --capture-dir."""
import argparse
import asyncio
from contextlib import aclosing
import getpass
import os
from pathlib import Path

from reolink_aio.api import Host
from reolink_aio.baichuan.baichuan import Baichuan
from reolink_aio.baichuan.media_decoder import MediaStream
from reolink_aio.enums import ConnectionEnum


async def probe(args):
    password = os.environ.get("DOORBELL_TEST_PASSWORD") or getpass.getpass("Camera password: ")
    host = Host(args.host, args.username, password, port=18080, use_https=False)
    client = Baichuan(args.host, args.username, password, host, port=args.port, connection_type=ConnectionEnum.tcp)
    try:
        async with asyncio.timeout(20):
            await client.login()
            print("PASS: native login")
            assert "VersionInfo" in await client.send(cmd_id=80)
            print("PASS: AES device-information query")
            ports = await client.get_ports()
            assert ports["server"]["port"] > 0
            print("PASS: configured network ports parsed by reolink_aio")
            for command, tag in [(56, "Enc"), (104, "SystemGeneral"), (146, "StreamInfoList"), (151, "AbilityInfo")]:
                assert tag in await client.send(cmd_id=command)
            print("PASS: encoding, clock, stream table and capabilities")
            if args.sync_clock:
                await client.synchronize_time()
                print("PASS: camera-local clock synchronization and readback")
            await client.send(cmd_id=31, ch_id=251)
            print("PASS: event subscription")
        if args.capture_dir:
            stream = MediaStream()
            preview = f'<?xml version="1.0"?><body><Preview version="1.1"><channelId>0</channelId><streamType>{args.stream}</streamType></Preview></body>'
            async with asyncio.timeout(20):
                async with aclosing(client.send_payload_stream(cmd_id=3, channel=0, body=preview, stop_cmd_id=4)) as chunks:
                    async for chunk in chunks:
                        stream.decode_chunk(chunk, final=False)
                        if len(stream.video_frames) >= 30 and len(stream.audio_frames) >= 10:
                            break
            assert stream.skipped_bytes == 0, "Invalid BCMedia bytes"
            destination = Path(args.capture_dir)
            destination.mkdir(parents=True, exist_ok=True)
            (destination / "native.h264").write_bytes(b"".join(stream.video_frames))
            (destination / "native.aac").write_bytes(b"".join(stream.audio_frames))
            print(f"PASS: {len(stream.video_frames)} H.264 frames, {len(stream.audio_frames)} AAC frames, {stream.width}x{stream.height}; zero skipped bytes")
    finally:
        await client.logout()
        await host.logout()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--sync-clock", action="store_true", help="Set the emulated camera clock to this client's time")
    parser.add_argument("--stream", choices=["mainStream", "subStream"], default="mainStream")
    parser.add_argument("--capture-dir", help="Opt in to saving a brief live camera/microphone sample")
    asyncio.run(probe(parser.parse_args()))
