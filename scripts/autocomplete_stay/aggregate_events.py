#!/usr/bin/env python3
"""Trigger autocomplete metric aggregation via admin endpoint.

Usage:
  python scripts/autocomplete_stay/aggregate_events.py --base-url http://localhost:18765 --admin-id 9001
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


def main() -> int:
    parser = argparse.ArgumentParser(description="Run autocomplete aggregation job")
    parser.add_argument("--base-url", default="http://localhost:18765", help="StayVista API base URL")
    parser.add_argument("--admin-id", default="9001", help="X-Admin-Id header value")
    args = parser.parse_args()

    url = f"{args.base_url.rstrip('/')}/v1/admin/autocomplete/aggregate"
    request = urllib.request.Request(
        url=url,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Admin-Id": str(args.admin_id),
        },
        data=b"{}",
    )

    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read().decode("utf-8")
            parsed = json.loads(body)
            print(json.dumps(parsed, ensure_ascii=False, indent=2))
            return 0
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8", errors="replace")
        print(f"HTTP {exc.code}: {payload}", file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001
        print(f"failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
