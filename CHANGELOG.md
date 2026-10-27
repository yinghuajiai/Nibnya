## NBT Editor_1.0.1-Update Log

1. A custom directory, whether it is a parent directory or a direct archive directory, is allowed to be loaded. The parent directory will exclude non-archives. If the archive is missing part of it, loading will be refused, and the parent directory will not be displayed.
Example:
/storage/emulated/0/download/ allowed
/storage/emulated/0/download/{archive name}/ allowed
2. Added anti-fool and fool-proof functions. If the user moves the edited archive of Blocktopograph to an old directory, a new directory, or a custom directory, the player will not be loaded.
3. Update jar to stable version, 2019~2020
4. Replace some NBT labels and solve that 12, 11, 7 are arrays and the rest are single groups.
5. ? No one should click that fast to prevent users from clicking and reloading too quickly, causing errors.


## NBT Editor_1.0.2-Update Log

1. Add English
2. About page pop-up window
3. Add a description of this class (automatic matching of item IDs is not implemented)
4. Add night and day time
5. UI redesign
6. Big update! Replace the original iq80 library with HiveGamesOSS and solve the Blocktopograph lock problem. It can be seamlessly switched and modified with Blocktopograph and naro.
7. A large number of nbts in the editing world or players cause crashes
8. Add Blocktopograph similar to dendrogram


## NBT Editor_1.0.3-Update Log

New

1. Add other players NBT more operations and modifications
2. Added more operations and modifications to edit map NBT
3. Added more operations and modifications for editing village NBT


Repair

1. Added the ability to capture fatal errors that usually cause "APP has stopped running" and save the error information to a local file.
2. Multi-threaded sharded reading, db player archives in this directory
3. Solve the problem that new applications do not have permission to use old application folders from Huawei
4. Add a sidebar to prevent the page from running out of space
5. Whenever data is loaded, it must be updated accurately
6. Repair, whether it is a public directory or a private directory, as long as it crashes once, no matter where it is, both sides will be saved once.
7. Add map multi-threading to speed up image processing and prevent user input from being too large.
8. When other players in this category edit the map or even the village and there is nothing in it, they can click on it. Although it is empty, players can create it themselves.
9. When loading other classes, when I click full-screen editing, it will be loaded by the player. Although the content is not lost, regardless of whether it is saved or not, once you return and press reload, it will be the player data.
10. When the user clicks on this class, it can be automatically loaded without having to step back to the page to load.
11. After loading archive a last time, the world, village, etc. were still saved. It should be automatically reset after the user switches archive b.
12. If you have loaded the players, maps, villages, etc. in the sidebar, it should seem to be left in the memory without reloading, otherwise you will have to reload it every time.
13. "Load archive" should directly load the selected archive instead of popping up a pop-up window and re-select it.
14. When editing any nbt data, for some relatively large amounts of data, you can use the search function to search for the corresponding class or class translation.
15. When there are many files for the player, map, and village, you can choose to use search fuzzy to find them.
16. When there are many files for the player, map, and village, there are check boxes to select and delete as many as you want.
17. The main interface should be more compact
18. Add a description of this class (implementation of automatic matching of item IDs), which needs to be refactored
19. I should classify these IDs in more detail, they must be separated into a file each
20. Enter the level in normal view first, and then switch to tree view, which will cause a refresh. Then enter in normal view, and switch again, and the entry will be repeated.
21. The navigation stack (navigationStack) in list mode stores "the data of the previous layer" (parent node) instead of "the data of the current layer"
22. Duplicate paths for view synchronization
23. The long bar above the full screen needs to copy the functions of the main interface.
24. If the user's archive is damaged, we will try to repair it and inform the user if it needs to be repaired, because some data will be lost.
25. If you place an empty file named .nomedia under the Bridge folder, the system media service will directly ignore this folder and no longer try to scan and index, thus completely eradicating this crash. From OS2.0.203.0.VMLCNXM (Xiaomi Xiaomi 13T Pro/Redmi K60 Ultra)
26. Whether it is public or private, there will be .nomedia in the working directory and backup directory to prevent the system media service scan of the domestic system from being modified.
27. Put the search key value, original class, world class, and old world class, some counters, scoreboard, etc.
28. The key-value content cannot be found and is automatically created.
29. When loading into the archive, automatically change the homepage title and put some commonly used display map names and map seeds. You can long press the map seeds to copy the seeds.
30. Apply multi-threading to all reading and writing. When the device condition does not allow it, there is a check box set. If it is checked, it is enabled by default. If it is not checked, it will be single-threaded. The multi-threading here is not only archives, but also players, maps, villages, etc.
31. Players don’t know how to read and remember these random and complex map folder names. It is better to traverse all map names.
32. Change the application signature and no longer use mt signature. You need to create a signature belonging to the application.
33. When building an application, there must be two versions: one is the official release version, and the other is the debug version.
34. It is enough to translate the resources into Chinese and English. Wait for the sidebar to be changed (front-loaded). If you need to add it later, you can make a decision later.
35. Multi-task switching + automatic draft saving until you decide to click "Save" to write to disk, or change the archive
36. Fixed the problem that custom puzzles cannot be used after internationalization.
37. Fixed the main title of the homepage, which will not switch to the map and add seeds.
38. Anti-null pointer crash repair (for the case where null adapter is passed in the search list)

## Nibnya_1.0.4a-Update Log (Test build)

> This is a pre-release test build; Debug build only

Changes (please read)

1. The app has been renamed: "Nibnya" (EN) / "樱喵NBT" (ZH), formerly NBT Editor
2. The package name has changed: yhjmew.minecraft.nbteditor -> yhjmew.nibnya. Android treats it as a different app, so it cannot be installed over the old version - you must uninstall first. Uninstalling removes the draft box and all settings; if you did not grant "All files access", the backups and recycle bin inside the old private directory are lost as well. Data under the public Download/ folder is not affected
3. The data folder has been renamed: Download/NbtEditor_Data/ -> Download/Nibnya/. On first launch the app asks whether to migrate; nothing is deleted automatically, and the old folder is kept until everything has been copied. You can also run it from "Settings -> Migrate old data"
4. The test build and the stable build now share the same signing key

Added

1. Recycle bin: deleting a world moves it to the recycle bin first (restore / delete forever / restore all / empty), with automatic cleanup (default 30 days; 7/30/90/custom)
2. Export .mcworld: packs a whole world into a file Minecraft can import directly
3. Draft box: replaces the old pop-up, unsaved edits are saved periodically and retrieved by the user, without interrupting
4. Clipboard rework: copying also writes to the system clipboard, in JSON / SNBT / plain text; pasting detects the format automatically
5. Export to file: to the Download folder, or to a folder you pick via SAF (needs no permission)
6. Full SAF (Storage Access Framework) support - see fix #1
7. List system: world picker / draft box / backup lists share one display format, a dynamic bottom tab bar and long-press menus
8. Multi-threading actually takes effect: file copy and map-art generation can be toggled and tuned
9. Added an xxxhdpi (192x192) app icon

Fixed

1. Saving in SAF mode silently "pretended to succeed" (most serious): write-back was not implemented for SAF paths at all, and two places reported success unconditionally (exit code never checked). Reads, writes and deletes now all go through DocumentFile, and write-back uses "temp dir -> rename old aside -> rename new into place -> roll back on failure -> delete old only after success"; any failure is reported honestly. Also fixed opening a world doing nothing in SAF mode (the two SAF load functions were dead code)
2. Two inconsistent definitions of the backup directory caused "clear this world's backups" to clear an empty directory while reporting success, and made the backup count always 0 when deleting a world (so the app never asked whether to delete the backups too). There is now a single definition
3. Tree view: new/deleted nodes were invisible, deleting a List element modified the wrong object, root-level fields failed, and saving was blocked
4. Player data lost after adding a List: the real root cause was deleting LevelDB index files (CURRENT / MANIFEST-*). Only LOCK / LOG are cleaned now
5. Large worlds misdetected as LOCK and refusing to open
6. Out-of-memory while generating map art: downsampled decoding + concurrency limits + timely recycling
7. Opening Settings immediately popped up the "custom" input box, the panel could not scroll, and labels were truncated
8. Crash when entering a custom interval / thread count (ArrayAdapter.remove on an immutable list)
9. The session-restore dialog showed the wrong target, and a discarded session came back to life
10. The tab bar would not stick to the bottom (AlertDialog wraps a custom view in a wrap_content container)
11. issue #6 permission problem: saving to a custom path no longer falsely claims "Shizuku is required"
12. Release build failure (var x clashing with a hand-written setX() on the JVM signature)

Improved

1. Reorganized packages by domain (nbt / db / editor / world / mapart / util)
2. Completed internationalization: EN/ZH string keys match exactly, hardcoded text removed
3. Crash logs are written to both the public and the private directory
4. Copy buffer 1KB -> 64KB plus zero-copy
5. All data-folder paths are defined in one place (AppDirs) instead of being scattered across 8 files


## Nibnya_1.0.4b-Update Log (Test build)

> Pre-release test build, Debug only. Bug fixes only, no new features

Fixed

1. "Nothing happens" after switching language: the backup list used a StateFlow, which does not emit when the new value equals the old one (scanning the same world twice returns equal results), so tapping "Restore backup" did nothing at all. Replaced with an event channel so every tap opens the dialog. Also, when a world is already selected the backup list opens for it directly instead of sending you back to the world picker
2. NBT values stayed Chinese after switching to English (real root cause): strings were resolved from the Application context, which does **not** follow the in-app language setting and always uses the system language. So with a Chinese system and an English app, buttons were English while values read "0: shengcun / 1: chuangzao". Now the context is built with the app language
3. Switching language left stale text behind: recreate() does not destroy the ViewModel, so the translation cache and already-rendered rows were never refreshed. Now the app is **fully restarted**; as requested, a confirmation dialog explains this first (edits are saved to the draft box), and it only proceeds if you accept
4. The permission dialog was "pushed away by the system settings page": there were two request paths on startup and one of them jumped to system settings with no dialog at all. The unconditional startup request is gone; there is now a single entry point that always explains first and only opens settings after you tap "Go to grant". Requests now happen when actually needed (save / switch path / manual button) and support "Don't ask again"
5. The old-data migration prompt never appeared: it was hidden behind the permission dialog, and the original "remind only once" design meant that being hidden or dismissed once silenced it forever. It now reminds **as long as the old folder still has content** (and stops naturally once it is migrated or emptied)

## Nibnya_1.0.4-Update Log (Stable)

> First stable release. Feature set matches the 1.0.4b test build. **The signing key has changed.**

### ⚠️ Signing key changed

The stable build uses a **brand-new signing key** (previously the temporary `debugmt.keystore` signature).

- **You do not need to uninstall anything.** The stable build is `yhjmew.nibnya` while test builds are `yhjmew.nibnya.debug` — different package names, so they coexist happily and can be installed side by side.
- You only need to uninstall if you previously had a build with the **same** `yhjmew.nibnya` package (i.e. an older stable build), because a signature change prevents installing over it.
- Neither the public `Download/Nibnya/` folder nor any test build's private data is affected.
- From now on both stable and test builds use this new key.

### Added

1. ROOT support: a unified privilege layer with ROOT / Shizuku / Normal modes. Root detection actually runs `su -c id` and checks for `uid=0`, so "su installed but not granted" and fake root are never misdetected.
2. Execution mode selector: one tap expands it in place in Settings (no pop-up). Choosing ROOT without root falls back to Shizuku, then to Normal, with a toast reporting the mode that actually took effect.
3. Custom su command template: you can change the `su -c {cmd}` form (Magisk / KernelSU / modified su). Three presets are included; tapping one runs a real test and tells you whether it works. A template must contain `{cmd}` to be saved.
4. "App internal" path: `/data/user/0/<package>/games/com.mojang/minecraftWorlds/`, shown only when real root is available and re-tested every time.
5. Atomic write-back: new data is fully staged in a temp directory on the same partition, the original is renamed aside, the new one takes its place; the old copy is removed only on success and any failure rolls back immediately. A structural self-check rolls back automatically on anomalies.
6. World thumbnails now genuinely appear: bytes are streamed through the privilege channel and decoded in memory instead of relying on direct file reads.
7. Three-line world list: name / folder ID / last played on separate rows, so long names no longer squeeze out the timestamp.
8. Edge-swipe sidebar: swipe right within 18dp of the left edge to open, swipe left anywhere to close.
9. Dark mode properly supported: two palettes, all 76 hard-coded colours replaced with theme colours; system dialogs (settings / backups / restore / root-node actions…) follow along.
10. Permission check moved into Settings and changed to a real probe.
11. Seed copies on a single tap, and the emoji is no longer copied.
12. Free crop now honours the row/column count: 5×5 correctly produces 25 maps instead of 1.
13. Square lock adjusts automatically: enabling "Lock ratio (square)" instantly snaps the crop box to the largest square.

### Fixed

1. **Saves written under root were unreadable by the game (most serious)**: files written as root are owned by `root:root` while Minecraft runs as its own uid with `rw-------` permissions, so the files existed and looked the right size, yet the game reported "Unable to connect to world" and the list showed an absurd `4167549952`. Ownership is now handed back to the owning app before the write completes.
2. **Ownership repair looped on itself**: the old logic preferred the ownership of the current db directory, so once corrupted to root it read root back and corrupted it again on every save. The app's own directory is now authoritative.
3. **The su command was split by the outer shell**: with `&&` in the command, only the first part ran as root while the rest ran as the ordinary app uid, so `cp` failed with `Permission denied`. The whole command is now passed as a single argument.
4. **Deleting an "app internal" world failed under root**: `/data/...` is only visible to root, so the native existence check always said the folder was missing.
5. **Automatic repair for already-broken saves**: ownership is verified when a world is opened, so saves corrupted by earlier builds can be rescued without re-saving.
6. **Android 16 / HyperOS only said "cannot write"**: it now probes real writability and gives an actionable fix.
7. **Three sidebar interaction bugs**: swiping stopped working after closing, taps on the scrim fell through to the main screen, and there was no right-to-left close gesture.
8. **First swipe only showed the scrim / wrong width**: the width read as 0 right after the view became visible.
9. **Command safety hardening**: `rm -rf` is limited to temp directories and the recycle bin; parent directories, wildcards and bare variables are refused. `mv` source and destination go through the same checks.
