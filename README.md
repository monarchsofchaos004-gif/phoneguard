# phoneguard — 長輩手機「接管守門哨」 / Elder Phone Takeover Guard

> **狀態：alpha（實驗版）。** 程式與 APK 可編譯；接管情境的實機調參尚未完成。歡迎試用回報。
> **Status: alpha.** Builds and runs; the takeover heuristics still need tuning on real devices. Issues welcome.

長輩可以自由安裝任何 App。只在某個 App「試圖接管整支手機」（蓋住整個畫面關不掉、藏掉返回鍵、
一直把自己拉回前景）的那一刻，守門哨跳出來把人帶回主畫面，並留一顆永遠在的救援鈕。
家人可以從自己的電腦或手機遠端放行／封鎖／救援，手機不見時也能遠端響鈴找手機——**長輩手機永遠不會被任何人連入**。

Elders can install anything they like. Only when an app tries to *take over the whole phone*
(un-closable full-screen overlay, hidden navigation bar, re-launching itself to the foreground) does
the guard step in: it forces the phone back to the home screen and keeps a rescue button on screen.
Family members can allow / block / rescue remotely from any device, and ring a lost phone to find it — **nothing ever connects into the elder's phone**.

## 為什麼不是「鎖住不給裝」 / Why not just lock the phone down
- 回復原廠＋裝置擁有者模式（Tier A）最穩，但要清掉手機現況，多數家庭不願意。
- 本專案走 **Tier B：無障礙服務、反應式**。不擋安裝，只在接管行為發生時介入。
  對商店裡那種關不掉的廣告遊戲有效；遇到也拿了無障礙權限、硬跟你搶的惡意 App **不保證每次贏**（見 [SECURITY.md](SECURITY.md) 的極限）。

## 它怎麼做 / How it works
| 接管手法 Takeover | 偵測 Detect | 對策 Response |
|---|---|---|
| 全螢幕覆蓋關不掉 | 非啟動器 App 長時間霸佔前景 | 連打 HOME 打斷＋救援鈕 |
| 藏返回／主畫面鍵 | 系統列長時間隱藏 | HOME＋救援面板 |
| 一直把自己拉回前景 | 送 HOME 後同一 App 立刻回來 | 升級：重複 HOME＋引導解除安裝 |
| 在封鎖清單上的 App | 一到前景 | 立刻踢回主畫面 |

### Shizuku 加強模式（選用）/ Optional Shizuku mode
有裝 [Shizuku](https://shizuku.rikka.app/) 並授權時，每次介入會多做無障礙服務做不到的事；沒有 Shizuku 一切照舊。
With [Shizuku](https://shizuku.rikka.app/) running and granted, each intervention also does what an accessibility service alone cannot. Without Shizuku nothing changes.

| 情況 Case | 加做 Extra action |
|---|---|
| 任何介入 Any intervention | `am force-stop`、收回「顯示在其他 App 上」(`appops … SYSTEM_ALERT_WINDOW deny`)、拿掉該 App 的無障礙服務 / force-stop, revoke draw-over-apps, remove its accessibility service |
| 封鎖清單 Block list | 再加停用（`pm disable-user`）；家人端 `allow <pkg>` 會一併解除停用 / also disable it; `allow <pkg>` from the console re-enables it |

- 系統 App、放行清單、PhoneGuard 自己一律不動 / System apps, the allow list and PhoneGuard itself are never touched.
- 一般手機重開機後 Shizuku 會停，要有人重新啟動它（家人幫忙）；停掉期間回到一般模式 / On non-rooted phones Shizuku stops after a reboot and must be started again; until then the guard runs in normal mode.
- 設定畫面按「④ Shizuku 加強模式授權」；家人端狀態會帶 `shizuku: true/false` / Tap ④ in the app; the console status shows `shizuku: true/false`.

## 架構 / Architecture
```
 family console (console/pg_console.py, any device with Python 3)
        │  outbound only                      outbound only  │
        ▼                                                    ▼
   relay: ntfy.sh (default) or self-hosted ntfy  ◄────────  elder's phone (app-android)
   commands: console → relay → phone (phone only polls)
   status:   phone → relay → console (console reads it as data only)
```
- 內容 AES-256-GCM 加密、topic 名由配對碼雜湊而來、指令帶時間戳＋nonce 防重放。細節見 [SECURITY.md](SECURITY.md)。
- 不需要任何伺服器：預設走公開的 ntfy.sh；想完全自己掌握就自架 ntfy，在 App 與主控各填一次網址。

## 安裝 / Install
> 從 0.1.1 起改用正式金鑰簽名；裝過更早測試版的要先解除安裝再裝。
> Since 0.1.1 the APK is signed with a release key; if you installed an earlier test build, uninstall it first.

1. 下載 APK：GitHub Releases（或 Actions 的 artifact），側載到長輩手機。
2. 開 App，依序按 ①②③ 授權（無障礙／顯示在其他 App 上／使用情況存取），再按「▶ 啟動守門」。
3. 畫面會顯示 **配對碼**。把它記到家人端（拍照即可，之後要換可按「重新產生」）。
4. 家人端（電腦、NAS、手機 Termux 都行）：
   ```bash
   pip install cryptography
   export PG_SECRET=<配對碼>
   python3 console/pg_console.py watch            # 看狀態
   python3 console/pg_console.py rescue           # 遠端救援：立刻回主畫面
   python3 console/pg_console.py block com.some.app   # 封鎖某 App
   python3 console/pg_console.py allow com.some.app   # 放行
   python3 console/pg_console.py lift 30          # 暫停守門 30 分鐘
   python3 console/pg_console.py guard            # 恢復守門
   python3 console/pg_console.py ring 60          # 找手機：大聲響 60 秒（靜音也會響）
   python3 console/pg_console.py stopring         # 停止響鈴
   ```
   自架轉發站：`export PG_NTFY=https://ntfy.example.com`（App 內「轉發站網址」填同一個）。

## 從原始碼編譯 / Build from source
- JDK 17、Android SDK（compileSdk 34）、Gradle 8.9（不需 wrapper）。
- `cd app-android && gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- GitHub Actions 已設定：每次 push 自動編譯（測試版＋未簽章正式版）；Release 只放開發者用自己金鑰簽好的 APK，手動上傳。

## 路線 / Roadmap
- [x] 守門核心（無障礙服務）＋救援浮鈕＋常駐服務＋加密轉發
- [x] 指令防重放、轉發站可設定
- [x] 遠端響鈴找手機（`ring`／`stopring`，鬧鐘聲道，靜音也會響）
- [x] 選用 Shizuku 加強模式：強制關閉／停用搶手機的 App、收回它的覆蓋與無障礙權限
- [ ] 接管情境實機調參（覆蓋／immersive／re-foreground 的門檻）
- [ ] 家人端做成 App 的「家長模式」（目前是 Python 主控）
- [ ] 偵測到「要求無障礙／裝置管理員」畫面時主動提醒長輩不要授權

## 授權 / License
MIT — see [LICENSE](LICENSE).
