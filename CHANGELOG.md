# Changelog

## 1.0.0 - 2026-10-02
- First release.
- Opens shulker boxes without placing them: right-click a box in the inventory screen (also the lower half of other container screens, and another box while one is open), or right-click the air with a box in the main hand or off hand.
- Boxes in the hotbar, the main inventory and the off hand. Stacked, locked, loot and command-made boxes are refused with a message; creative and spectator players can't use it.
- Every change in the screen is written into the box item in the same server call that applies it, through a listener on the server's menu. The box is checked before every click and pinned while open, saved before death drops spawn and closed safely on quit, shutdown and `/shulkeranywhere disable`. If the listener can't be added or stops working, the feature turns itself off.
- `/shulkeranywhere status | reload | enable | disable` (alias `/sa`, permission `shulkeranywhere.admin`), permission `shulkeranywhere.use` for everyone.
- Automated dupe tests in `dupe-tests/`: a packet-level bot against a local Paper 26.3 server.
