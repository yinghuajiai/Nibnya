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
