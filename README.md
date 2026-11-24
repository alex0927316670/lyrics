# MP3 動態歌詞下載（Android）

選一個手機資料夾 → 自動找出底下所有 `.mp3` → 到 [木蘭詞 mulanci.org](https://www.mulanci.org/) 抓動態歌詞 → 存成同檔名的 `.lrc`（放在 mp3 旁邊）。

## 使用方式

1. 按「📁 選擇資料夾」，在系統視窗中選擇放 MP3 的資料夾，按「使用這個資料夾」並允許存取。
2. 視需要勾選：
   - **包含子資料夾**（預設勾）
   - **覆蓋已存在的 .lrc**（不勾＝已經有歌詞的就略過）
   - **UTF-8 加 BOM**（部分車機或舊播放器看到亂碼時再勾）
3. 按「▶ 開始下載歌詞」，畫面會顯示每首歌的結果，最後列出沒抓到的歌曲。

## 怎麼找歌

- 先讀 MP3 內的「歌手／歌名」標籤，再用檔名 `歌手 - 歌名.mp3`（也會試 `歌名 - 歌手`，開頭的音軌編號如 `03.` 會自動去掉）。
- 用木蘭詞的 A–Z 歌手索引找到歌手，再從該歌手的完整歌曲清單比對歌名（會忽略「(Live)」「- 電影主題曲」這類附註）。
- 只存「有時間軸 `[mm:ss.xx]`」的動態歌詞；網站只有純文字歌詞的歌會列為「沒有動態歌詞」。
- 支援繁體與簡體：繁體找不到時會改用簡體頁面比對。

## 注意事項

- 木蘭詞有 Cloudflare 防護，APP 透過手機內建的瀏覽器核心 (WebView) 讀網頁；若跳出「確認您是人類」驗證，畫面會顯示網頁，請點一下完成驗證，之後自動繼續。

- **第一次執行**會先下載木蘭詞的歌手索引（約 81 頁，約 2–3 分鐘），之後快取 30 天；「更新歌手索引」可強制重抓。
- 木蘭詞有流量限制（太快會回 HTTP 429）。程式每次請求間隔 1.5 秒，遇到限制會自動等待重試，所以**每首歌約需 3–5 秒**，數百首請耐心等。
- 執行期間螢幕會保持常亮；請讓 APP 留在前景，切走可能被系統暫停。

## 編譯 APK

### 方法 A：GitHub 自動編譯（不需安裝任何軟體）
1. 在 GitHub 建一個新 repository（例如 `LrcDownloader`）。
2. 在 repo 頁面按 **Add file → Upload files**，把這個資料夾**裡面的所有檔案與資料夾**拖進去（包含 `.github` 資料夾），按 Commit。
   - 若瀏覽器不讓你拖 `.github` 隱藏資料夾，可先上傳其他檔案，再用 **Add file → Create new file**，檔名輸入 `.github/workflows/build-apk.yml`，貼上該檔內容。
3. 到 **Actions** 頁籤等編譯完成（約 3–5 分鐘，綠色勾勾）。
4. 到 repo 的 **Releases → latest**，用手機點 `LrcDownloader.apk` 下載安裝（需允許「安裝未知來源應用程式」）。

### 方法 B：Android Studio
用 Android Studio 開啟此資料夾 → Build → Build APK(s)。

## 程式結構

| 檔案 | 說明 |
|---|---|
| `app/src/main/java/tw/lrc/downloader/MainActivity.java` | 介面、資料夾選擇 (Storage Access Framework)、掃描 mp3、寫入 .lrc |
| `app/src/main/java/tw/lrc/downloader/MulanciClient.java` | 木蘭詞索引／歌曲比對／LRC 擷取、限速與 429 重試（純 Java） |
| `app/src/main/java/tw/lrc/downloader/WebFetcher.java` | 用 WebView 讀取網頁、處理 Cloudflare 人機驗證 |
| `.github/workflows/build-apk.yml` | GitHub Actions 自動編譯並發佈到 Releases |
