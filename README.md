<div align="center">

<img height="120" src="./Screenshots/Nibnya.png">

<h1 align="center">Nibnya</h1>

<!-- SHIELD GROUP -->
[![Version][version-shield]][version-link]
[![License][license-shield]][license-link]
[![Issues][issues-shield]][issues-link]

[![Forks][forks-shield]][forks-link]
[![Stars][stars-shield]][stars-link]
[![Contributors][contributors-shield]][contributors-link]

<!-- 社交与外部链接 -->
[![Bilibili][bilibili-shield]][bilibili-link]
[![QQ Group][qq-shield]][qq-link]
[![Telegram][tg-shield]][tg-link]

English · [Chinese](./README.zh-CN.md) · [Change log](./CHANGELOG.md)

</div>

<br/>

> [!TIP]
> It's finally halfway stabilized, and there's still some stuff that will be added later on

> [!WARNING]
> I know there will be problems, but I still have to apologize<br/>
> **This application code is written by deepseek v4**<br/>

> [!TIP]
> Do you want to participate in this, add application functions, modify functions, etc.<br/>
> Please fork and submit a PR, or patch and tidy it up yourself after forking.

<details>
<summary><kbd>Table of contents</kbd></summary>

#### directory listing

- [🌸 Project Introduction](#-Project-Introduction)
- [🖼️ App Screenshot](#-App-Screenshot)
- [🚀 Get started quickly](#-Get-started-quickly)
- [📄 Update Log](#-Update-Log)
- [👥 Thank you list](#-Thank-you-list)
- [📜 Open source agreement](#-Open-source-agreement)


</details>

<br/>

## 🌸 Project introduction
The imitation Blocktopograph is not directly adapted from him.
Just like a tire, a similar

## 🖼️ App screenshots
<details>
<summary><kbd>🌸 View app interface preview</kbd></summary>

<div align="center">
  <br/>
  <!-- 这里直接引用底部的链接名是不行的，必须写路径或者直接用引用式 -->
  <!-- 建议如果需要限宽，直接在 details 里用 img 标签 -->
  <img src="./Screenshots/en/Home page.jpg" width="80%" alt="Home page">
  <br/>
  <img src="./Screenshots/en/World exhibition.jpg" width="80%" alt="World exhibition">
  <br/>
  <img src="./Screenshots/en/Full-screen with tree view display.jpg" width="80%" alt="Full-screen with tree view display">
  <br/>
  <img src="./Screenshots/en/Root node operation.jpg" width="80%" alt="Root node operation">
  <br/>
  <img src="./Screenshots/en/Switch the display mode.jpg" width="80%" alt="Switch the display mode">
  <br/>
  <img src="./Screenshots/en/Restore backup.jpg" width="80%" alt="Restore backup">
  <br/>
  <img src="./Screenshots/en/sidebar.jpg" width="80%" alt="sidebar">
  <br/>
  <img src="./Screenshots/en/Player data.jpg" width="80%" alt="Player data">
  <br/>
  <img src="./Screenshots/en/Map data.jpg" width="80%" alt="Map data">
  <br/>
  <img src="./Screenshots/en/More operations.jpg" width="80%" alt="More operations">
  <br/>
  <img src="./Screenshots/en/Map puzzle warning.jpg" width="80%" alt="Map puzzle warning">
  <br/>
  <img src="./Screenshots/en/Select the size of the puzzle.jpg" width="80%" alt="Select the size of the puzzle">
  <br/>
  <img src="./Screenshots/en/Village data.jpg" width="80%" alt="Village data">
  <br/>
  <img src="./Screenshots/en/Long press menu.jpg" width="80%" alt="Long press menu">
  <br/>
  <img src="./Screenshots/en/Open by name.jpg" width="80%" alt="Open by name">
  <br/>
  <img src="./Screenshots/en/Global system data.jpg" width="80%" alt="Global system data">
  <br/>
</div>

</details>

## 🚀 Get started quickly
### 📺 Introduction to use

Introduction and usage tutorial：

[![Watch the video](./Screenshots/Minecraft_Android_NBT_Editor.png)](https://www.bilibili.com/video/BV16dfnBrEzT)


> [!TIP]
> Make sure you **have the basics[Shizuku](https://github.com/RikkaApps/Shizuku)use**, the app will ask for<br/>
> Otherwise, you will be **unable to access Android/data** Minecraft files.<br/>
> **Currently only supports Android 9 ~ 13**<br/>
> Obtaining all file permissions is for the download directory to read and write to the old directory and the private directory.

## 📄 Change log
<details open>
<summary><kbd>Resolved</kbd></summary>

- [x] Recycle bin: deleting a world moves it there first, so nothing is unrecoverable
- [x] Export `.mcworld`: pack a whole world into a file Minecraft can import directly
- [x] Draft box: unsaved edits are kept in a draft box instead of interrupting with a pop-up
- [x] Clipboard rework: copies also go to the system clipboard, in JSON / SNBT / plain text
- [x] Export to file: to the Download folder, or to any folder you pick via SAF
- [x] **Full SAF support**: read / write / delete all go through DocumentFile, and saving is no longer "falsely reported as successful"
- [x] Unified world picker / draft box / backup lists, with a bottom tab bar and long-press menus
- [x] **Fixed: player data lost after adding a List** — the root cause was deleting LevelDB index files
- [x] Fixed: large worlds misdetected as LOCK and refusing to open
- [x] Fixed: the tree view is now fully usable (add / edit / delete / search / save)
- [x] Fixed: out-of-memory when generating map art
- [x] Fixed: two inconsistent backup directory definitions ("clear backups" cleared an empty folder while reporting success)
- [x] Fixed: opening Settings popped up the "custom" input box, and the panel could not scroll
- [x] Fixed: crash when entering a custom draft interval / thread count
- [x] Fixed: the session-restore dialog showed the wrong target; a discarded session no longer comes back
- [x] Fixed: the tab bar would not stick to the bottom
- [x] Fixed: issue #6 — saving to a custom path no longer falsely claims "Shizuku is required"
- [x] Multi-threading settings actually take effect (file copy + map art, with tunable concurrency)
- [x] Complete internationalization (EN / ZH string keys match exactly)
- [x] Code reorganised by domain (`nbt/` `db/` `editor/` `world/` `mapart/` `util/`)
- [x] Renamed to Nibnya / 樱喵NBT, new icon, and the data folder `NbtEditor_Data/` → `Nibnya/`

</details>

<details open>
<summary><kbd>To do</kbd></summary>

- [ ] Low memory on large worlds (the app can be killed by the system)
- [ ] Auto-update
- [ ] UI polish
- [ ] Backup policy (stop backing up on every save; time-based / opt-in / manual)
- [ ] Map plane view and editing (view / modify / move chunks)
- [ ] Multiple worlds open at once (the same world must not be opened twice)
- [ ] Blocktopograph-style theme
- [ ] Preset templates (player / village / map)
- [ ] One-click tools (32767 equipment, command-line items, …)
- [ ] A third page with handy Bedrock utilities
- [ ] Upgrading old worlds across major versions
- [ ] Multi-user support
- [ ] KMP cross-platform (Windows / Linux desktop)

</details>

_Other ideas and modifications are still being implemented_

## 👥 Thank you list
 [@Yejdhi](https://github.com/Yejdhi) Provided me with many methods and modification ideas<br/>
 [@LeviMC](https://github.com/LiteLDev) Provided me with methods and ideas for modifications<br/>
(It is also recommended to take a look at LeviMC’s open source projects)

## 📜 Open source agreement

This project uses **[AGPL 3.0](https://www.gnu.org/licenses/agpl-3.0)** protocol



<!-- 这里是底部的链接定义-->

[version-shield]: https://img.shields.io/badge/Version-v1.0.4a-369eff?style=flat-square&labelColor=black
[version-link]: https://github.com/yinghuajimew/Nibnya/releases

[issues-shield]: https://img.shields.io/github/issues/yinghuajimew/Nibnya?color=ff80eb&labelColor=black&style=flat-square
[issues-link]: https://github.com/yinghuajimew/Nibnya/issues

[forks-shield]: https://img.shields.io/github/forks/yinghuajimew/Nibnya?color=8ae8ff&labelColor=black&style=flat-square
[forks-link]: https://github.com/yinghuajimew/Nibnya/network/members

[stars-shield]: https://img.shields.io/github/stars/yinghuajimew/Nibnya?color=ffcb47&labelColor=black&style=flat-square
[stars-link]: https://github.com/yinghuajimew/Nibnya/stargazers

[contributors-shield]: https://img.shields.io/github/contributors/yinghuajimew/Nibnya?color=c4f042&labelColor=black&style=flat-square
[contributors-link]: https://github.com/yinghuajimew/Nibnya/graphs/contributors

[license-shield]: https://img.shields.io/badge/license-AGPL_3.0-blue?style=flat-square&labelColor=black
[license-link]: https://www.gnu.org/licenses/agpl-3.0

<!-- 社交链接定义 -->

<!-- Bilibili: 少女粉 #FB7299 -->
[bilibili-shield]: https://img.shields.io/badge/Bilibili-FB7299?style=flat-square&labelColor=black&logo=bilibili&logoColor=white
[bilibili-link]: https://www.bilibili.com/video/BV16dfnBrEzT

<!-- QQ交流群: 腾讯蓝 #12B7F5 -->
[qq-shield]: https://img.shields.io/badge/QQ_group-12B7F5?style=flat-square&labelColor=black&logo=qq&logoColor=white
[qq-link]: https://qm.qq.com/cgi-bin/qm/qr?_wv=1027&group_code=1077349003

<!-- Telegram频道: 电报蓝 #26A5E4 -->
[tg-shield]: https://img.shields.io/badge/Telegram-26A5E4?style=flat-square&labelColor=black&logo=telegram&logoColor=white
[tg-link]: https://t.me/yhjmew_minecraft_nbteditor