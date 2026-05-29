# Platform Output Contracts

Load this before producing code or implementation scaffolds. Use the contract that matches the user's target platform. If the platform is unknown, ask before coding.

## Shared contract for all platforms

Always emit:

1. shared semantic tokens: color, type, space, radius, elevation, motion, state, z-index/layering if relevant
2. one reference screen tied to the product's actual primary action
3. realistic content plus long, empty, loading, and error cases
4. accessibility notes and implementation hooks
5. 3 to 5 component specs that explain the departure from defaults

Never hardcode raw hex, spacing, or duration values inside component bodies when the platform supports tokens or constants.

## Design-token-only output

Provide:

- `tokens.json` using semantic groups: `color`, `type`, `space`, `radius`, `elevation`, `motion`, `breakpoint`, `state`
- naming rules and alias rules
- light and dark modes
- adaptive density overrides if chosen
- component usage notes

## Web: React, Next.js, Vue, Svelte, or static HTML/CSS

Prefer:

- CSS custom properties or a typed token file
- responsive layout rules with breakpoints or container queries
- semantic HTML landmarks
- keyboard focus states, visible focus ring tokens, ARIA only when native semantics are insufficient
- reduced-motion media query

Typical files:

```text
src/styles/tokens.css
src/styles/theme.css
src/components/[Component].tsx or equivalent
src/app/[feature]/page.tsx or equivalent
```

Do not default to shadcn/Tailwind card stacks unless the user chose that language. If Tailwind is requested, extend the theme with tokens instead of inline arbitrary values.

## Android: Jetpack Compose or Compose Multiplatform

Prefer:

- `Color.kt`, `Type.kt`, `Shape.kt`, `Spacing.kt`, `Motion.kt`, `Theme.kt`
- `CompositionLocal` or equivalent for spacing and density tokens
- Material 3 only as a foundation, not as the visual identity
- previews for light, dark, and edge cases
- `contentDescription`, semantics, and minimum touch targets

For Compose Multiplatform, keep shared tokens in `commonMain` and flag Android-only APIs.

## iOS: SwiftUI

Prefer:

- `DesignTokens.swift` or separate `ColorTokens`, `TypeTokens`, `SpaceTokens`, `MotionTokens`
- `EnvironmentKey` or equivalent for spacing/density when useful
- Dynamic Type support and VoiceOver labels
- light/dark assets or code-defined colors
- previews for light, dark, accessibility text size, and long content

Respect iOS navigation and control conventions unless the user chose a branded departure.

## Flutter

Prefer:

- `design_tokens.dart`
- `theme.dart` using `ThemeData` plus extensions for custom tokens
- widgets that consume tokens through theme extensions
- semantic labels, focus traversal, and text scaling behavior
- preview or sample states where the project setup supports it

Do not bury custom values inside widget constructors.

## React Native

Prefer:

- `tokens.ts`
- `theme.ts`
- platform-aware spacing, typography, and motion constants
- accessibility props for labels, roles, and states
- safe-area and touch-target handling
- explicit iOS/Android divergence where platform fidelity matters

## Desktop app: Electron, Tauri, native desktop, or enterprise UI

Prefer:

- density controls if users perform repeated work
- keyboard shortcuts, focus order, command palette, and resizable regions
- split panes, inspectors, tables, and high-information states when appropriate
- platform menu or toolbar conventions where useful

## No-code design handoff

If the user wants Figma or design documentation rather than code, emit:

- token tables
- component anatomy
- interaction states
- adaptive layout notes
- accessibility checklist
- reference screen description
- implementation-ready copy and behavior specs
