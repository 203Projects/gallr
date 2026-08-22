#!/usr/bin/env python3

import argparse
import sys
import tomllib
from pathlib import Path


EXPECTED_VERIFY_JWT = {
    "delete-account": True,
    "geocode-address": True,
    "invite-editor": True,
    "launch-rsvp": False,
    "legacy-catalog-mirror": False,
    "legacy-catalog-mirror-receiver": False,
    "outbox-delivery": False,
    "outbox-worker": False,
    "promoted-nearby": False,
    "record-exhibition-view": False,
}


def validate(root: Path) -> list[str]:
    errors: list[str] = []
    config_path = root / "supabase" / "config.toml"
    functions_root = root / "supabase" / "functions"
    try:
        with config_path.open("rb") as config_file:
            config = tomllib.load(config_file)
    except (OSError, tomllib.TOMLDecodeError) as error:
        return [f"supabase/config.toml could not be loaded: {error}"]

    auth = config.get("auth", {})
    if auth.get("enable_signup") is not True:
        errors.append(
            "auth.enable_signup must be true so Gallr and Gallery can create shared identities."
        )
    if auth.get("enable_anonymous_sign_ins") is not False:
        errors.append(
            "auth.enable_anonymous_sign_ins must be false for identified Gallr accounts."
        )
    email = auth.get("email", {})
    if email.get("enable_signup") is not True:
        errors.append(
            "auth.email.enable_signup must be true for Gallr password and Gallery OTP signup."
        )
    if email.get("enable_confirmations") is not True:
        errors.append(
            "auth.email.enable_confirmations must be true for verified password signup."
        )
    sms = auth.get("sms", {})
    if sms.get("enable_signup") is not False:
        errors.append("auth.sms.enable_signup must be false until SMS signup is reviewed.")

    discovered = {
        path.parent.name for path in functions_root.glob("*/deno.json")
    }
    expected = set(EXPECTED_VERIFY_JWT)
    for name in sorted(expected - discovered):
        errors.append(f"Expected Edge Function directory is missing: {name}.")
    for name in sorted(discovered - expected):
        errors.append(
            f"Edge Function {name} has no reviewed verify_jwt contract in the validator."
        )

    configured = config.get("functions", {})
    for name, expected_verify_jwt in EXPECTED_VERIFY_JWT.items():
        function = configured.get(name)
        if not isinstance(function, dict):
            errors.append(f"supabase/config.toml is missing [functions.{name}].")
            continue
        if function.get("enabled") is not True:
            errors.append(f"functions.{name}.enabled must be true.")
        if function.get("verify_jwt") is not expected_verify_jwt:
            errors.append(
                f"functions.{name}.verify_jwt must be "
                f"{str(expected_verify_jwt).lower()}."
            )
        expected_import_map = f"./functions/{name}/deno.json"
        if function.get("import_map") != expected_import_map:
            errors.append(
                f"functions.{name}.import_map must be {expected_import_map}."
            )
        expected_entrypoint = f"./functions/{name}/index.ts"
        if function.get("entrypoint") != expected_entrypoint:
            errors.append(
                f"functions.{name}.entrypoint must be {expected_entrypoint}."
            )

    for name in sorted(set(configured) - discovered):
        errors.append(
            f"Configured Edge Function has no direct deno.json package: {name}."
        )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate gallery-product Auth and Edge Function boundaries."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Repository root (defaults to the validator's repository).",
    )
    arguments = parser.parse_args()
    errors = validate(arguments.root.resolve())
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        "Validated self-service identity Auth and "
        f"{len(EXPECTED_VERIFY_JWT)} Edge Function boundaries."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
