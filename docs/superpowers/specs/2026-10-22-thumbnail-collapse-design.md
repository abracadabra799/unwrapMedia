# Collapsible thumbnail preview

Approved: collapse only the thumbnail preview, redistribute its space equally to primary image and motion video, and keep a restore control always visible in a shared preview toolbar. Closing and restoring returns to the existing equal-width layout. Overall preview height still uses the existing divider.

User correction: a file without a thumbnail must still show the thumbnail pane with a no-thumbnail message by default. Its toggle remains enabled and it can be collapsed/restored exactly like an available thumbnail. Loading and unsupported-codec messages remain distinct.

Use Material3 and bilingual KO/EN labels. Store the expanded preference in TabState so switching tabs preserves each file's setting. No dependencies, decoding changes or release bump. GIF filmstrip remains its own presentation without this toolbar. Preserve primary-image and motion-video composition identity during toggling.
