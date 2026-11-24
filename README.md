# MP3 動態歌詞下載（Android）

Android APP，兩個功能：

1. **輸入檔名抓歌詞**：輸入「歌手 - 歌名」（可含 `.mp3`），到 [木蘭詞 mulanci.org](https://www.mulanci.org/) 抓動態歌詞，存成 `.lrc`。
   資料夾內有同名 mp3 → `.lrc` 放在該 mp3 旁邊；沒有 → 放在選擇的資料夾。已有同名 `.lrc` 會覆蓋。
2. **匯出 MP3 清單**：掃描資料夾（可含子資料夾）所有 `.mp3`，在選擇的資料夾產生 `mp3清單_日期_時間.csv`（UTF-8 BOM，Excel 可直接開），並跳出分享選單傳到電腦。

## CSV 欄位

| 欄位 | 說明 |
|---|---|
| 相對路徑 | 相對於選擇資料夾的子資料夾，例如 `華語/` |
| 檔名 | 含 `.mp3` |
| 主檔名 | 不含副檔名；電腦程式產生 `.lrc` 時用這個檔名 |
| 已有LRC | Y / N |
| 大小(MB) | |
| 歌手／歌名／專輯(標籤)、長度(秒) | 勾選「同時讀取 MP3 內的歌手／歌名／長度」才有 |

## 找歌方式

- 用木蘭詞的 A–Z 歌手索引找到歌手，再從該歌手的完整歌曲清單比對歌名（忽略「(Live)」「- 電影主題曲」這類附註）。
- 只存有時間軸 `[mm:ss.xx]` 的動態歌詞。繁體找不到時改用簡體頁面比對。

## 注意事項

- 木蘭詞有 Cloudflare 防護，APP 透過手機內建的瀏覽器核心 (WebView) 讀網頁；若跳出「確認您是人類」驗證，畫面會顯示網頁，請點一下完成驗證，之後自動繼續。

- **第一次執行**會先下載木蘭詞的歌手索引（約 81 頁，約 2–3 分鐘），之後快取 30 天；「更新木蘭詞歌手索引」可強制重抓。
- 木蘭詞有流量限制（太快會回 HTTP 429），程式每次請求間隔 1.5 秒，遇到限制會自動等待重試。

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
