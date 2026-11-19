# Implementation plan

Keep existing Kotlin models, repositories, view models and platform services. Own their retained state in a native-facing Kotlin application boundary, bridge state using cancellable observers, and delegate user actions back to Kotlin. Keep a single application instance across SwiftUI navigation and scene-size changes. Compile the portable mobile application sources into the dedicated Apple framework alongside the Swift bridge. Keep the framework's Swift import name and exported model names. The Android Compose module uses those same source files.

Use a compact navigation stack and adaptive list/detail layout driven by the same selection and routing state. Respect native safe areas and use available-width and obstruction information from the host instead of assuming device categories.

## Constitution check

Shared-First keeps all portable decisions in Kotlin. Test-First applies to changed shared behavior and state boundary lifecycle. Native acceptance checks cover compact and wide layouts and iOS build validation.
