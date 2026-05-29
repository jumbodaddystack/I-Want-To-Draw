# Anti-Cookie-Cutter Doctrine

Use this reference before design divergence and final audit. The purpose is to identify common AI/template UI defaults and replace them with product-specific choices.

| Cookie-cutter tell | Distinctive alternative |
|---|---|
| untouched default platform colors, such as Material purple or generic Tailwind blue | named palette derived from brand, emotional intent, and usage context |
| decorative blue/purple or teal/green gradients | flat intentional color, or one signature gradient with a named job |
| every section is a centered rounded card with shadow on a gray background | varied surfaces: flat fields, edges, dividers, insets, full-bleed zones, or elevation only when meaningful |
| every gap is 16px, 16dp, or the platform default | rhythm: tight clusters, generous section breaks, and density tied to task frequency |
| default type stack with bold as the only emphasis | type scale with contrast in size, weight, tracking, role, and optional display voice |
| symmetric dashboard grids by habit | content-driven hierarchy with a clear focal point and useful asymmetry where appropriate |
| hero, three feature cards, CTA template | layout follows the product's real primary action |
| emoji or generic illustrations as iconography | coherent icon family, illustration policy, or no illustration at all |
| placeholder text shaping the design | realistic content, edge cases, empty states, and long labels drive layout decisions |
| stock fades only | named motion signature with duration, easing, and reduced-motion fallback |
| generic centered empty/error/loading states | state copy and actions specific to the user's product and recovery path |
| density mismatch | density chosen for audience expertise, frequency, posture, device, and stress level |

## Distinctive does not mean gimmicky

If the product needs convention for trust, safety, or speed, choose convention deliberately. Ask the user to accept the trade-off rather than sneaking into defaults.

## Direction quality test

A good direction can be recognized without seeing the logo. It has a point of view across color, type, spacing, layout, state design, and motion. A weak direction is mostly an accent color plus generic cards.
