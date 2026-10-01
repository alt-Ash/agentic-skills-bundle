---
{{DESIGN_TOKENS_FRONTMATTER}}
---

# Design System

<!-- Generated stub — invoke the `project-initializer` agent to fill this in -->
<!-- Keep this file updated as design decisions are made and tokens are defined -->
<!-- See AGENT.md for instructions on when and how to update this file -->

**UI Framework**: {{UI_FRAMEWORK}}
**Component Library**: {{COMPONENT_LIBRARY}}
**Styling**: {{STYLING_APPROACH}}

---

## Overview

{{BRAND_OVERVIEW}}
_Brand personality, target audience, emotional tone. Example: "Internal admin tool. Professional, dense, data-focused. No decorative elements — clarity over style." This is the fallback context for any stylistic decision not covered by a specific token._

---

## Colors

{{COLORS}}
_One bullet per color with hex and usage rule. Example:_
_- **Primary (#1A1C1E):** Deep ink for headlines and core text — maximum readability._
_- **Accent (#B8422E):** Earthy red — use exclusively for primary actions and highlights._
_- **Neutral (#F7F5F2):** Warm limestone for page backgrounds._
_Include rationale and usage constraint, not just the hex value._

---

## Typography

{{TYPOGRAPHY}}
_Font family + named scale levels with role. Example:_
_Font: Inter. display-lg: 48px/700, -0.04em (hero/temperature readings). body-md: 16px/400, 1.6lh (default reading). label-sm: 12px/600, 0.05em uppercase (metadata/captions)._
_Note any weight adjustments for legibility on specific backgrounds._

---

## Layout

{{LAYOUT}}
_Base unit, max-width, grid, gutters, and key named spacing values. Example:_
_8px base unit. Max-width 1200px. 12-col grid with 24px gutters. Card padding: 24px. Section margin: 40px. Component gap: 16px._

---

## Elevation & Depth

{{ELEVATION}}
_Named levels with concrete CSS or description of technique. Example:_
_Level 1: page background. Level 2: content cards (white surface). Level 3: overlays/modals (box-shadow: 0 8px 32px rgba(0,0,0,0.08)). No heavy drop shadows — tonal layers preferred._

---

## Shapes

{{SHAPES}}
_Named radii and when to apply. Example:_
_sm (4px): inputs, tags. md (8px): cards, dialogs. xl (24px): buttons, pills. full (9999px): avatars, chips._

---

## Components

{{COMPONENT_PATTERNS}}
_Per component type: name, variants, key styles, states. Example:_
_**Button — Primary**: bg-primary text-white rounded-xl px-6 py-3. Hover: bg-primary/90._
_**Button — Secondary**: border border-primary text-primary rounded-xl px-6 py-3._
_**Input**: bg-surface border-outline rounded-md px-4 py-3. Focus: border-primary ring._
_Add a subsection per component family as the system grows._

---

## Accessibility Standards

{{ACCESSIBILITY_STANDARDS}}
_WCAG level, screen reader requirements, keyboard nav expectations, enforcement tooling. Example:_
_WCAG AA. Automated axe checks run in UI tests. Keyboard nav required for all interactive elements._

---

## Do's and Don'ts

{{DOS_AND_DONTS}}
_Practical guardrails, one per line. Example:_
_- Do use primary color only for the single most important action per screen._
_- Don't mix rounded-xl and sharp corners in the same view._
_- Do maintain 4.5:1 contrast ratio for normal text (WCAG AA)._
_Add entries as patterns are established and anti-patterns are discovered._
