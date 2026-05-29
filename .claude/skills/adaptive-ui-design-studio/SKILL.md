---
name: adaptive-ui-design-studio
description: interactive platform agnostic ui design studio for creating distinctive adaptive design systems before implementation. use when a user is starting a new app, feature, website, dashboard, redesign, or cross-platform product interface and needs guided discovery, divergent visual directions, design tokens, component specs, responsive/adaptive behavior, and a reference implementation scaffold for web, mobile, desktop, ios, android, flutter, react native, compose, swiftui, or design-token-only workflows while avoiding generic ai-generated ui defaults.
---

# Adaptive UI Design Studio

Run an interactive product-design studio that asks before it defaults. The goal is to help the user choose a distinctive, platform-appropriate visual identity, lock it into named tokens and component rules, then emit a reference screen or scaffold for the requested platform.

This skill is for design discovery and design-system creation, not immediate generic screen generation. Do not write implementation code until the user has explicitly chosen a design direction and the required system forks are locked.

## Core rule: ask, do not default

Treat every consequential design decision as an interaction gate. Use the best available question mechanism in the current environment:

1. If an `AskUserQuestion` style tool exists, use it for gates.
2. If no structured question tool exists, ask numbered questions in chat and stop for answers.
3. If the user has already answered a gate in provided context, do not ask it again.
4. Batch related questions, up to four at a time.
5. Offer 2 to 4 genuinely different options plus `Other`.
6. Mark an option as `Recommended` only when the recommendation is grounded in the product context, audience, constraints, or user-provided evidence.

Never silently assume brand, audience, density, platform, accessibility stance, or visual direction.

## Load references when needed

- Load `references/anti-cookie-cutter-doctrine.md` before divergence and again before the final audit.
- Load `references/platform-output-contracts.md` before emitting platform-specific code, token files, or component specs.
- Load `references/adaptive-layout-gates.md` when the target includes multiple breakpoints, tablets, desktop, foldables, responsive web, or cross-platform output.

## Workflow

### Phase 0: frame the studio

Read all provided context: product idea, app screens, screenshots, code, brand assets, competitor names, platform targets, device classes, and constraints.

Respond in 3 to 5 sentences with:

- the product or feature as understood
- who uses it
- the primary action the UI must make effortless
- any explicit platform or accessibility constraints already known

If the primary action or target platform is unclear, make that the first interaction gate. Do not propose visual directions yet.

### Phase 1: discovery gates

Gather the raw material that makes the design specific. Ask only what is not already known.

Cover these dimensions in 1 to 2 batches:

1. Primary job and emotional target: the one action users need to complete and how they should feel while doing it.
2. Audience and context: expertise, frequency, environment, device posture, assistive needs, low-end device constraints, network realities.
3. Platform and output target: web, iOS, Android, Flutter, React Native, desktop, design tokens only, or multi-platform.
4. Brand anchors: existing colors, logo, typography, voice, legal constraints, accessibility requirements, dark-mode expectations.
5. Anti-references and admired references: 1 to 3 products to avoid and 1 to 3 products to learn from, with reasons.

Use anti-references as high-signal constraints. Distinctiveness should come from the user's product, not from decorative novelty.

### Phase 2: divergence gate

Synthesize discovery into 2 or 3 named design directions. The directions must differ in mood, layout philosophy, color strategy, type strategy, density, motion personality, and signature moment. Do not present three accent-color variants of the same system.

For each direction, provide:

- name
- one-line concept
- color strategy, not final hex values yet
- type strategy
- layout philosophy and focal point
- surface policy, such as flat, layered, edge-led, card-light, or card-heavy only if justified
- motion signature
- one signature moment that could become the product's identity
- one risk or trade-off

Ask the user to choose one direction, blend directions, or choose `Other`. Do not proceed until the direction is explicit. If the user blends or gives a custom answer, restate the merged direction briefly and confirm before locking.

### Phase 3: lock the system

Translate the chosen direction into concrete design-system decisions. Decide what is directly implied by the user's choices. Ask only where taste or product constraints genuinely fork.

Typical lock gates:

- light-first, dark-first, or equal parity
- density: compact, comfortable, spacious, or adaptive by context
- corner language: sharp, soft, pill, mixed, or platform-native within token limits
- dynamic theming: forbidden, user-accent only, brand-preserving, or platform-native allowed
- adaptive layout: single-column, list-detail, rail plus content, canvas, split pane, or custom
- platform fidelity: platform-native controls, custom branded controls, or hybrid

Produce the locked system in this structure:

```markdown
## Design System: [direction name]

### Color tokens
| Token | Light | Dark | Role |
|---|---:|---:|---|
| brand.primary | #... | #... | primary action only |
| ink.strong | #... | #... | headlines and high-emphasis text |
| surface.base | #... | #... | app background |
| surface.raised | #... | #... | intentional elevation only |
| accent.signature | #... | #... | the one loud moment |
| state.error | #... | #... | destructive/error feedback |
| state.success | #... | #... | completion/positive feedback |

### Type tokens
| Token | Family or role | Weight | Size/line/tracking | Use |
|---|---|---:|---|---|

### Space and rhythm
Base grid and scale, with rules for tight clusters, generous breaks, and platform touch targets.

### Shape and elevation
Surface tiers, corner tokens, border policy, shadow policy, tonal elevation policy.

### Motion tokens
Durations, easing, reduced-motion behavior, and the signature transition.

### Adaptive behavior
Breakpoints or size classes, navigation pattern, content reflow, and density shifts.
```

Use semantic token names. Do not reduce the system to raw color swatches.

### Phase 4: emit implementation scaffold

Before code, load `references/platform-output-contracts.md` and follow the selected platform contract. If the user requested multiple platforms, emit a shared token layer first, then platform adapters.

All implementation output must follow these rules:

- Use named tokens, not inline hex values or magic spacing numbers.
- Keep state hoisted or platform-equivalent.
- Include accessibility semantics, labels, focus order, contrast notes, keyboard/screen-reader behavior where relevant.
- Include realistic content and long, empty, loading, and error edge cases.
- Respect platform conventions unless the user chose a custom branded departure.
- Show a reference screen tied to the actual primary action, not a generic landing template.
- Include 3 to 5 component specs explaining how each component diverges from default platform or template behavior.

If code would be too long for the current turn, prioritize the shared tokens, one reference screen, and component specs. State exactly what remains.

### Phase 5: anti-cookie-cutter self-audit

Load `references/anti-cookie-cutter-doctrine.md`, then audit the result honestly. Any failure must be fixed or explicitly justified as a user-chosen trade-off.

Use this table:

```markdown
## Anti-Cookie-Cutter Self-Audit
| Check | Pass? | Evidence |
|---|:---:|---|
| Palette is derived from brand or intent, not default platform colors |  |  |
| Gradients, if present, are a signature moment rather than filler |  |  |
| Surfaces vary and are not all shadowed rounded cards |  |  |
| Spacing has rhythm and rejects uniform gaps everywhere |  |  |
| Type scale has contrast in size, weight, tracking, or role |  |  |
| Layout has a focal point tied to the primary action |  |  |
| Realistic content and edge cases shaped the layout |  |  |
| Motion has a named signature and reduced-motion fallback |  |  |
| Empty, error, and loading states use product-specific voice and action |  |  |
| Density matches task frequency, user expertise, and device context |  |  |
| Accessibility requirements are present from the first scaffold |  |  |
| At least one signature moment belongs to this product |  |  |
```

End with:

1. a one-paragraph design rationale
2. the decisions the user made
3. open questions and next implementation steps

## Special paths

### Existing design system

If the user provides an existing design system, do not reinvent it. Run Phase 0 and a trimmed discovery gate, then ask interpretation forks: where the primary action lives, which token carries emphasis, how density adapts, and where a signature moment can exist without violating the system. Continue with Phase 4 and Phase 5.

### Strict brand kit

If brand tokens are non-negotiable, skip broad color divergence. Diverge on layout philosophy, type hierarchy, interaction tone, adaptive behavior, motion, and component surface policy.

### Compliance-heavy product

If the product is finance, healthcare, enterprise admin, safety, or government, do not equate distinctiveness with whimsy. Surface the trade-off between convention, trust, clarity, and memorability as a gate.

### Multi-platform product

Prefer shared semantic tokens plus platform adapters. Ask whether platform-native fidelity or brand consistency matters more when they conflict.

## Must not

- Do not jump straight to generating a screen or default theme.
- Do not offer false-choice options that differ only cosmetically.
- Do not invent brand facts, user research, competitor details, or legal constraints.
- Do not override stated accessibility, density, or brand requirements in pursuit of distinctiveness.
- Do not manufacture novelty when convention is safer or more useful.
- Do not emit platform-specific APIs before the platform target and direction are locked.
