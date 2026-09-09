#!/usr/bin/env python3

import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("validate-config.py")
SPEC = importlib.util.spec_from_file_location("product_config", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Product configuration validator could not be loaded.")
PRODUCT_CONFIG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PRODUCT_CONFIG)


def write_fixture(
    root: Path,
    *,
    global_signup: bool = True,
    anonymous_signup: bool = False,
    email_signup: bool = True,
    email_confirmations: bool = True,
    sms_signup: bool = False,
    overrides: dict[str, bool] | None = None,
    omitted: set[str] | None = None,
) -> None:
    overrides = overrides or {}
    omitted = omitted or set()
    functions_root = root / "supabase" / "functions"
    functions_root.mkdir(parents=True)
    sections = [
        "[auth]",
        f"enable_signup = {str(global_signup).lower()}",
        f"enable_anonymous_sign_ins = {str(anonymous_signup).lower()}",
        "",
        "[auth.email]",
        f"enable_signup = {str(email_signup).lower()}",
        f"enable_confirmations = {str(email_confirmations).lower()}",
        "",
        "[auth.sms]",
        f"enable_signup = {str(sms_signup).lower()}",
    ]
    for name, verify_jwt in PRODUCT_CONFIG.EXPECTED_VERIFY_JWT.items():
        function_root = functions_root / name
        function_root.mkdir()
        (function_root / "deno.json").write_text("{}\n", encoding="utf-8")
        if name in omitted:
            continue
        sections.extend(
            [
                "",
                f"[functions.{name}]",
                "enabled = true",
                f"verify_jwt = {str(overrides.get(name, verify_jwt)).lower()}",
                f'import_map = "./functions/{name}/deno.json"',
                f'entrypoint = "./functions/{name}/index.ts"',
            ]
        )
    (root / "supabase" / "config.toml").write_text(
        "\n".join(sections) + "\n",
        encoding="utf-8",
    )


class ProductConfigTest(unittest.TestCase):
    def test_free_beta_excludes_retired_stripe_function_packages(self) -> None:
        self.assertNotIn(
            "create-launch-checkout", PRODUCT_CONFIG.EXPECTED_VERIFY_JWT
        )
        self.assertNotIn(
            "stripe-launch-webhook", PRODUCT_CONFIG.EXPECTED_VERIFY_JWT
        )

    def test_accepts_self_service_auth_and_expected_function_boundaries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root)

            self.assertEqual(PRODUCT_CONFIG.validate(root), [])

    def test_rejects_closed_signup_and_unverified_or_disabled_email_signup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(
                root,
                global_signup=False,
                email_signup=False,
                email_confirmations=False,
            )

            errors = PRODUCT_CONFIG.validate(root)

            self.assertTrue(any("auth.enable_signup" in error for error in errors))
            self.assertTrue(any("auth.email.enable_signup" in error for error in errors))
            self.assertTrue(
                any("auth.email.enable_confirmations" in error for error in errors)
            )

    def test_rejects_anonymous_and_sms_signup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root, anonymous_signup=True, sms_signup=True)

            errors = PRODUCT_CONFIG.validate(root)

            self.assertTrue(
                any("auth.enable_anonymous_sign_ins" in error for error in errors)
            )
            self.assertTrue(any("auth.sms.enable_signup" in error for error in errors))

    def test_rejects_a_changed_jwt_boundary_and_missing_function_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(
                root,
                overrides={"geocode-address": False},
                omitted={"launch-rsvp"},
            )

            errors = PRODUCT_CONFIG.validate(root)

            self.assertTrue(
                any("geocode-address.verify_jwt" in error for error in errors)
            )
            self.assertTrue(any("launch-rsvp" in error for error in errors))

    def test_rejects_a_changed_invite_editor_jwt_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root, overrides={"invite-editor": False})

            errors = PRODUCT_CONFIG.validate(root)

            self.assertTrue(any("invite-editor.verify_jwt" in error for error in errors))

    def test_rejects_disabling_delete_account_gateway_authentication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root, overrides={"delete-account": False})

            errors = PRODUCT_CONFIG.validate(root)

            self.assertTrue(
                any("delete-account.verify_jwt" in error for error in errors)
            )

    def test_rejects_changing_mobile_analytics_anonymous_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root, overrides={"mobile-analytics": True})

            errors = PRODUCT_CONFIG.validate(root)

            self.assertTrue(
                any("mobile-analytics.verify_jwt" in error for error in errors)
            )


if __name__ == "__main__":
    unittest.main()
