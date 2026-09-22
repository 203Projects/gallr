# Verification

Run focused red/green tests before broader gates:

```sh
./gradlew composeApp:testAndroidHostTest
./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck androidApp:lintDebug androidApp:assembleDebug
./gradlew composeApp:linkReleaseFrameworkIosSimulatorArm64
```

Run `deno task test` and `deno task check` in changed functions with Deno 2.9.4. For SQL, run lineage validator and full disposable database workflow. Admin/gallery use their package gates.

Manual release matrix: Android/iPhone/iPad, light/dark/system, KO/EN, cover/missing/slow cover, long title, Retry, back during render, double-share, dismissal and re-share. Send to KakaoTalk/Instagram only with authorized test accounts. Staging email rehearsal uses only explicit sink; never real operators.

For this release, the user waived staging and approved bounded production
verification. Follow the exact scope and outstanding gates in
[verification.md](verification.md#approved-change-of-plan); production execution
and real-device destination checks remain pending.
