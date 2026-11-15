#!/usr/bin/env python3
"""Read NVR capabilities/push schedules; never modifies NVR configuration.

Prompts for the NVR password without echoing it. Login tokens are not printed
or saved. Run: python3 scripts/inspect_nvr_push.py 192.0.2.10
"""
import argparse
import getpass
import json
import urllib.parse
import urllib.request


def inspect(host, username, password):
    token = None

    def call(command, param=None, action=0):
        query = {"cmd": command}
        if token:
            query["token"] = token
        request = urllib.request.Request(
            "http://" + host + "/api.cgi?" + urllib.parse.urlencode(query),
            data=json.dumps([{"cmd": command, "action": action, "param": param or {}}]).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)[0]

    login = call("Login", {"User": {"userName": username, "password": password}})
    if login.get("code") != 0:
        raise RuntimeError("NVR login rejected; no automatic retries performed")
    token = login["value"]["Token"]["name"]
    try:
        ability = call("GetAbility", {"User": {"userName": username}})
        capabilities = ability.get("value", {}).get("Ability", {})
        print("GLOBAL", json.dumps({k:v for k,v in capabilities.items() if any(t in k.lower() for t in ("push", "schedule", "authorization"))}))
        channels = capabilities.get("abilityChn", [])
        if len(channels)>1:
            print("CHANNEL_1_ABILITIES", json.dumps({k:v for k,v in channels[1].items() if any(t in k.lower() for t in ("ai", "visitor", "doorbell", "dingdong", "supportmd"))}))
        for command, param in [("GetDevInfo", {}), ("GetPushCfg", {}), ("GetPushV20", {"channel": 1}), ("GetEvents", {"channel": 1})]:
            try:
                result=call(command,param,1)
                if command=="GetDevInfo":
                    info=result.get("value",{}).get("DevInfo",{})
                    result={k:v for k,v in info.items() if k in ("name","model","firmVer","hardVer","channelNum")}
                print(command,json.dumps(result))
            except Exception as error:
                print(command,type(error).__name__)
    finally:
        try:
            call("Logout")
        except Exception:
            print("Logout unavailable; session will expire")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("--username", default="admin")
    args = parser.parse_args()
    inspect(args.host, args.username, getpass.getpass("NVR password: "))
