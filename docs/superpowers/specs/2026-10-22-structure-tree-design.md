# Structure tree refinement

The user approved the proposed VS Code-inspired compact tree and 010 Editor-inspired information alignment.

Use existing Compose Material3, no new dependencies. Render neutral monospaced node names, small category indicators, subtle hierarchy guides, and fixed 24dp rows. Remove the category stripe, outlined name pills, proportional size bars and inline offsets. Keep summaries muted and ellipsized; place byte sizes in a fixed right-aligned 76dp column. Offsets remain in properties and text export.

Clicking a row selects it; only its disclosure control expands/collapses it. Preserve external selection reveal, warnings, copy, expand/collapse all, and parser behavior. Localize toolbar and column labels using the current app language. Keep nested content clipped to its column so deep trees cannot displace size values.

Builder choices: 16dp indentation, 20dp disclosure hit area, 12sp names, 11sp secondary text. No release version bump.
