# Adaptive Layout Gates

Use this reference when the output spans screen sizes, platforms, device postures, or input modes.

## Ask these gates when not already answered

1. Primary device context: phone, tablet, desktop, foldable, watch, TV, kiosk, or mixed.
2. Input mode: touch, mouse, keyboard, remote, stylus, voice, or assistive switch.
3. Session style: glance, one-handed quick task, focused work session, monitoring, creation, or review.
4. Content scale: short known lists, long feeds, dense tables, media canvas, forms, maps, timelines, or dashboards.
5. Navigation adaptation: bottom bar, tabs, side rail, sidebar, split view, command palette, breadcrumb, or custom.
6. Density adaptation: fixed density, compact on desktop, comfortable on touch, user-controlled density, or role-based density.

## Common adaptive patterns

| Pattern | Use when | Avoid when |
|---|---|---|
| single pane | the task is linear and mobile-first | users compare or reference multiple objects |
| list-detail | users select and inspect many items | the primary action is creation or capture |
| split pane | comparison and context preservation matter | phone-only or highly emotional consumer flows |
| rail plus content | 5 or fewer top-level destinations on medium/large screens | one-task products with no navigation depth |
| canvas plus inspector | creation, editing, maps, diagrams, or media workflows | read-only content or quick transactions |
| dashboard focal panel | monitoring or executive overview | users need step-by-step completion |

## Output expectations

Document how layout reflows at each relevant breakpoint or size class. Specify what becomes hidden, collapsed, promoted, pinned, or transformed. Do not simply scale the phone design upward.
