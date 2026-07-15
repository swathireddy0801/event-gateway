#!/usr/bin/env python3
"""
Mints a test HS256 JWT for manually calling the Event Gateway locally.
No dependencies beyond the standard library.

Usage:
    python3 scripts/generate-test-token.py [scope] [secret]

    scope   defaults to "events:write"
    secret  defaults to the dev secret baked into application.yml
            (JWT_CLIENT_SECRET's default) - pass your own if you've
            overridden it.
"""
import base64
import hashlib
import hmac
import json
import sys
import time

DEFAULT_SECRET = "dev-only-client-shared-secret-change-me-32bytes+"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def main():
    scope = sys.argv[1] if len(sys.argv) > 1 else "events:write"
    secret = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_SECRET

    header = {"alg": "HS256", "typ": "JWT"}
    now = int(time.time())
    payload = {
        "sub": "test-client",
        "scope": scope,
        "iat": now,
        "exp": now + 3600,
    }

    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(payload).encode())}"
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    token = f"{signing_input}.{b64url(signature)}"

    print(token)


if __name__ == "__main__":
    main()
