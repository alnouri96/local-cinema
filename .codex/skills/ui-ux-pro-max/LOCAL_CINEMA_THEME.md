# Local Cinema design rules

- Use Soft Black (#171717) for primary surfaces and Gold (#E5A93B) for focus, selection and primary actions. Keep labels legible over adjacent surfaces. Include a label or icon for status in addition to color.
- On Android TV and Leanback, make every action reachable by D-Pad. Use a clear gold focus ring, predictable directional order, visible focus after scrolling, and focus restoration when closing dialogs. Provide readable typography and spacing for a living-room viewing distance.
- Ship every label, message, dialog and accessibility description in English and Arabic. Use Android string resources with matching placeholders, start/end alignment, and bidirectional formatting for mixed Latin and Arabic text. Check mirrored navigation and content in Arabic RTL and English LTR.
- Reuse the owning app's design tokens and controls. Keep phone touch targets at least 48dp and leave safe space for the keyboard, system bars, and app navigation.
- Apply these project rules to skill search results and generated design-system suggestions.
