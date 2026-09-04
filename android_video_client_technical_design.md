# Tài liệu thiết kế kỹ thuật --- Android Video Client sử dụng YouTube/InnerTube

## 1. Mục tiêu

Tài liệu mô tả kiến trúc kỹ thuật cho một ứng dụng Android duyệt, tìm
kiếm và phát nội dung video theo mô hình tương tự YouTube.

Stack đề xuất:

-   **Kotlin**
-   **Jetpack Compose**
-   **MVVM / Unidirectional Data Flow**
-   **Hilt** cho Dependency Injection
-   **Retrofit + OkHttp** cho HTTP
-   **Media3 / ExoPlayer** cho playback
-   **Room** cho cache dữ liệu cục bộ
-   **Paging 3** cho feed/search pagination
-   **DataStore** cho preferences
-   Coroutines + Flow

Ứng dụng tách phần giao diện, dữ liệu YouTube và playback thành các
module độc lập để hạn chế ảnh hưởng khi API hoặc cơ chế phân phối media
thay đổi.

> **Lưu ý phạm vi:** InnerTube là giao diện nội bộ/không phải API công
> khai ổn định dành cho ứng dụng bên thứ ba. Thiết kế dưới đây coi phần
> tích hợp InnerTube là một adapter có thể thay thế. Tài liệu không đặc
> tả cơ chế vô hiệu hóa, loại bỏ hoặc né quảng cáo của YouTube.

------------------------------------------------------------------------

## 2. Kiến trúc tổng thể

``` text
┌───────────────────────────────────────────────┐
│                  Android App                  │
├───────────────────────────────────────────────┤
│ UI                                            │
│ Compose + Navigation                          │
│                                               │
│ Home │ Search │ Player │ History │ Settings   │
├───────────────────────────────────────────────┤
│ Presentation                                  │
│ ViewModel + StateFlow + UI State              │
├───────────────────────────────────────────────┤
│ Domain                                        │
│ UseCase + Repository Interface                │
├───────────────────────┬───────────────────────┤
│ YouTube Data Layer    │ Playback Layer        │
│                       │                       │
│ InnerTubeClient       │ StreamResolver        │
│ ResponseParser        │ FormatSelector        │
│ Repository            │ MediaSourceFactory    │
│                       │ PlayerManager         │
├───────────────────────┴───────────────────────┤
│ Infrastructure                                │
│ Retrofit │ OkHttp │ Room │ DataStore │ Media3 │
└───────────────────────────────────────────────┘
```

Nguyên tắc quan trọng:

``` text
UI
 ↓
ViewModel
 ↓
UseCase
 ↓
Repository
 ↓
Data Source / Playback Source
```

UI không trực tiếp gọi InnerTube và cũng không trực tiếp xử lý URL
media.

------------------------------------------------------------------------

## 3. Cấu trúc module

``` text
app/
│
├── navigation/
├── di/
└── MainActivity.kt

core/
├── common/
├── network/
├── database/
├── youtube/
└── playback/

feature/
├── auth/
├── home/
├── search/
├── player/
├── history/
├── channel/
├── playlist/
└── settings/

data/
├── repository/
├── local/
└── remote/
```

### 3.1 `core:youtube`

Chịu trách nhiệm giao tiếp với nguồn dữ liệu YouTube.

``` text
core:youtube
│
├── InnerTubeClient
├── InnerTubeRequestFactory
├── PlayerResponseParser
├── SearchResponseParser
├── BrowseResponseParser
└── YouTubeModels
```

Không để model JSON của API đi thẳng lên UI.

Ví dụ:

``` kotlin
interface YouTubeDataSource {
    suspend fun search(query: String): SearchResult
    suspend fun getVideo(videoId: String): VideoInfo
    suspend fun getPlaybackInfo(videoId: String): PlaybackInfo
}
```

------------------------------------------------------------------------

## 4. Network Layer

Retrofit chịu trách nhiệm request API; OkHttp xử lý các concern chung.

``` text
Retrofit
   ↓
OkHttp
   ├── headers
   ├── timeout
   ├── retry policy
   ├── logging (debug only)
   └── authentication/session adapter
```

Không hard-code credential, token hoặc thông tin nhạy cảm trong source
code.

Các response từ endpoint nội bộ phải được xem là **không ổn định**.
Parser cần chịu được:

-   field bị thiếu;
-   field đổi vị trí;
-   response renderer thay đổi;
-   endpoint trả lỗi;
-   video bị giới hạn;
-   stream URL hết hạn;
-   yêu cầu xác thực bổ sung.

------------------------------------------------------------------------

## 5. Authentication

Nếu ứng dụng có backend riêng:

``` text
Login
  ↓
Validate credentials / OAuth
  ↓
Backend
  ↓
Access Token + Refresh Token
  ↓
Secure Storage
  ↓
Home
```

Không trộn authentication của backend ứng dụng với session/cookie của
dịch vụ video.

Các thành phần:

``` text
AuthRepository
TokenStore
AuthInterceptor
TokenAuthenticator
```

`ViewModel` chỉ làm việc với `AuthRepository`.

------------------------------------------------------------------------

## 6. Home Feed

Home feed sử dụng Paging 3:

``` text
HomeScreen
   ↓
HomeViewModel
   ↓
Pager
   ↓
HomeRepository
   ↓
Remote Data Source
```

Model UI:

``` kotlin
data class VideoCard(
    val id: String,
    val title: String,
    val thumbnail: String,
    val channelName: String,
    val durationText: String?
)
```

Không giữ toàn bộ response API trong Compose state.

------------------------------------------------------------------------

## 7. Search

Flow:

``` text
TextField
   ↓
StateFlow<String>
   ↓
debounce(300 ms)
   ↓
distinctUntilChanged()
   ↓
SearchRepository
   ↓
PagingSource
   ↓
LazyColumn
```

Ví dụ:

``` kotlin
query
    .debounce(300)
    .distinctUntilChanged()
    .flatMapLatest { searchRepository.search(it) }
```

Có thể bổ sung:

-   lịch sử tìm kiếm;
-   suggestion;
-   category/filter;
-   pagination;
-   retry khi mạng lỗi.

------------------------------------------------------------------------

## 8. Playback Architecture

Playback là subsystem độc lập.

``` text
PlayerScreen
     ↓
PlayerViewModel
     ↓
StreamResolver
     ↓
PlaybackInfo
     ↓
FormatSelector
     ↓
MediaSourceFactory
     ↓
PlayerManager
     ↓
Media3 / ExoPlayer
```

### 8.1 `StreamResolver`

``` kotlin
interface StreamResolver {
    suspend fun resolve(videoId: String): PlaybackInfo
}
```

Domain model:

``` kotlin
data class PlaybackInfo(
    val videoId: String,
    val durationMs: Long,
    val videoTracks: List<VideoTrack>,
    val audioTracks: List<AudioTrack>,
    val subtitles: List<SubtitleTrack>
)
```

Ví dụ track:

``` kotlin
data class VideoTrack(
    val url: String,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val bitrate: Long?
)

data class AudioTrack(
    val url: String,
    val mimeType: String,
    val bitrate: Long?,
    val language: String?
)
```

URL media nên được coi là dữ liệu **ngắn hạn**, không phải permanent
URL.

------------------------------------------------------------------------

## 9. Format Selection

`FormatSelector` lựa chọn track dựa trên:

``` text
User preference
      +
Network bandwidth
      +
Device capability
      +
Codec support
      ↓
Selected tracks
```

Ví dụ:

``` kotlin
interface FormatSelector {
    fun select(
        playback: PlaybackInfo,
        preference: PlaybackPreference
    ): SelectedTracks
}
```

Preference:

``` kotlin
data class PlaybackPreference(
    val maxHeight: Int?,
    val preferWifiQuality: Boolean,
    val preferredAudioLanguage: String?
)
```

Không để logic chọn 1080p/1440p/4K nằm trong `PlayerScreen`.

------------------------------------------------------------------------

## 10. Media3 / ExoPlayer

Media3 hỗ trợ các pipeline adaptive phổ biến như HLS và DASH.

``` text
Selected media source
        ↓
MediaItem
        ↓
MediaSource
        ↓
ExoPlayer
        ↓
Audio/Video Renderer
```

Player cần hỗ trợ:

-   play/pause;
-   seek;
-   playback speed;
-   track selection;
-   subtitle;
-   quality selection;
-   fullscreen;
-   Picture-in-Picture;
-   lifecycle;
-   resume position.

------------------------------------------------------------------------

## 11. PlayerManager

Không nên tạo player mới mỗi lần Compose recomposition.

``` kotlin
interface PlayerManager {

    fun play(source: PlaybackSource)

    fun pause()

    fun seekTo(positionMs: Long)

    fun release()
}
```

PlayerManager quản lý:

``` text
ExoPlayer instance
Playback state
Current MediaItem
Track selection
Lifecycle
Audio focus
```

Compose chỉ observe state.

------------------------------------------------------------------------

## 12. PlayerViewModel

``` kotlin
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val streamResolver: StreamResolver,
    private val playerManager: PlayerManager,
    private val historyRepository: HistoryRepository
) : ViewModel() {

    fun play(videoId: String) {
        viewModelScope.launch {
            val playback = streamResolver.resolve(videoId)

            // Convert domain playback information into
            // a supported playback source.
            val source = createPlaybackSource(playback)

            playerManager.play(source)
        }
    }
}
```

Điểm quan trọng:

``` text
PlayerViewModel
     X
không biết chi tiết JSON/renderer của InnerTube.
```

Nếu backend/API thay đổi, chủ yếu sửa:

``` text
InnerTubeClient
ResponseParser
StreamResolver implementation
```

thay vì sửa UI/player toàn bộ.

------------------------------------------------------------------------

## 13. Lưu vị trí xem

Room:

``` text
watch_history
────────────────────────
video_id
position_ms
duration_ms
updated_at
completed
```

Flow:

``` text
Playback
   ↓
periodic checkpoint
   ↓
HistoryRepository
   ↓
Room
```

Không cần ghi DB mỗi frame/second. Có thể checkpoint theo khoảng thời
gian hợp lý và khi:

-   pause;
-   app background;
-   đổi video;
-   player release.

------------------------------------------------------------------------

## 14. Cache

Chia cache thành hai loại.

### Metadata cache

Room:

``` text
Video metadata
Search history
Watch history
Channel metadata
Playlist metadata
```

### Media cache

Media3 cache:

``` text
CDN
 ↓
CacheDataSource
 ↓
Local cache
 ↓
ExoPlayer
```

Nên có quota và eviction policy.

------------------------------------------------------------------------

## 15. Error Handling

Chuẩn hóa lỗi thành domain error:

``` kotlin
sealed interface PlaybackError {

    data object Network : PlaybackError

    data object VideoUnavailable : PlaybackError

    data object AuthenticationRequired : PlaybackError

    data object StreamExpired : PlaybackError

    data object UnsupportedFormat : PlaybackError

    data object ParserFailure : PlaybackError

    data class Unknown(
        val cause: Throwable
    ) : PlaybackError
}
```

UI không cần hiểu HTTP code cụ thể.

------------------------------------------------------------------------

## 16. Stream URL hết hạn

Không giả định URL media tồn tại lâu dài.

``` text
play()
 ↓
resolve()
 ↓
URL
 ↓
playback
 ↓
403 / expired
 ↓
re-resolve
 ↓
new URL
 ↓
resume(position)
```

Nên giới hạn retry để tránh loop vô hạn.

------------------------------------------------------------------------

## 17. Offline Mode

Room có thể phục vụ:

-   history;
-   metadata đã cache;
-   search history;
-   playlist metadata của chính ứng dụng.

Nếu hỗ trợ media offline, phải có lifecycle rõ ràng:

``` text
Download request
   ↓
DownloadManager
   ↓
Media cache
   ↓
Download DB
```

Việc lưu ngoại tuyến nội dung của dịch vụ bên thứ ba phải tuân theo
quyền sử dụng và chính sách tương ứng.

------------------------------------------------------------------------

## 18. Security

Không lưu:

``` text
password plaintext
access token plaintext
refresh token trong log
cookie/session trong log
media URL trong analytics
```

Release build:

``` text
HTTP body logging = OFF
Sensitive headers logging = OFF
Debug endpoints = OFF
```

API key/token không nên được xem là bí mật chỉ vì đặt trong APK; APK có
thể bị reverse-engineer.

------------------------------------------------------------------------

## 19. Dependency Injection

Hilt graph:

``` text
NetworkModule
    ↓
HttpClient
    ↓
InnerTubeClient
    ↓
YouTubeDataSource
    ↓
Repository
    ↓
UseCase
    ↓
ViewModel
```

Playback:

``` text
PlaybackModule
    ↓
ExoPlayer
    ↓
PlayerManager
    ↓
PlayerViewModel
```

------------------------------------------------------------------------

## 20. Package Structure

``` text
com.example.videoapp

├── app
│   ├── MainActivity
│   └── navigation
│
├── core
│   ├── common
│   ├── network
│   ├── database
│   ├── youtube
│   └── playback
│
├── data
│   ├── repository
│   ├── local
│   └── remote
│
├── domain
│   ├── model
│   ├── repository
│   └── usecase
│
└── feature
    ├── auth
    ├── home
    ├── search
    ├── player
    ├── channel
    ├── playlist
    ├── history
    └── settings
```

------------------------------------------------------------------------

## 21. Data Flow hoàn chỉnh

``` text
User selects video
        │
        ▼
PlayerScreen
        │
        ▼
PlayerViewModel
        │
        ▼
StreamResolver
        │
        ▼
YouTubeDataSource
        │
        ▼
InnerTube adapter
        │
        ▼
Playback response
        │
        ▼
ResponseParser
        │
        ▼
PlaybackInfo
        │
        ▼
FormatSelector
        │
        ▼
PlaybackSource
        │
        ▼
PlayerManager
        │
        ▼
Media3 / ExoPlayer
        │
        ▼
Playback
```

------------------------------------------------------------------------

## 22. Nguyên tắc đối với quảng cáo và playback integrity

Không thiết kế core player dựa trên:

``` text
Manifest
 ↓
Detect advertisement
 ↓
Remove segment
 ↓
Rewrite manifest
```

Đây là coupling rất mạnh với implementation của nguồn video và có thể
phá vỡ ngay khi server thay đổi.

Đối với YouTube API Services/embedded playback, chính sách YouTube hiện
hành cấm API client chặn, sửa hoặc thay thế quảng cáo và đặt yêu cầu về
playback integrity. Vì vậy nếu sản phẩm cần tuân thủ API chính thức,
playback phải sử dụng phương thức được YouTube hỗ trợ và giữ nguyên các
yêu cầu đó.

Ở cấp kiến trúc, nên giữ:

``` text
PlaybackSourceResolver
```

như abstraction chung để implementation có thể được thay thế mà không
ảnh hưởng UI.

------------------------------------------------------------------------

## 23. Khả năng chống thay đổi API

Đây là phần quan trọng nhất nếu nghiên cứu InnerTube.

Không:

``` text
UI → raw InnerTube JSON
```

Mà:

``` text
InnerTube JSON
      ↓
DTO
      ↓
Parser
      ↓
Domain Model
      ↓
Repository
      ↓
UI
```

Khi response thay đổi:

``` text
Old response
     X

New response
     ↓
Update Parser
     ↓
Domain API giữ nguyên
     ↓
UI không đổi
```

------------------------------------------------------------------------

## 24. Testing Strategy

### Unit Test

``` text
ResponseParser
FormatSelector
Repository
UseCase
ViewModel
```

Đặc biệt lưu sample response để regression-test parser.

### Integration Test

``` text
HTTP
 ↓
Parser
 ↓
Repository
 ↓
Domain Model
```

### Player Test

Test:

-   stream load;
-   seek;
-   resume;
-   URL expiry;
-   network disconnect;
-   audio/video synchronization;
-   track switching;
-   lifecycle;
-   PiP.

------------------------------------------------------------------------

## 25. Kiến trúc đề xuất cuối cùng

``` text
┌───────────────────────────────┐
│          Compose UI           │
└──────────────┬────────────────┘
               │
        ViewModel / State
               │
┌──────────────▼────────────────┐
│             Domain            │
│ UseCase + Repository Contract │
└──────────────┬────────────────┘
               │
       ┌───────┴────────┐
       │                │
┌──────▼──────┐   ┌─────▼──────────┐
│ YouTube Data│   │ Playback Core  │
│             │   │                │
│ API Adapter │   │ StreamResolver │
│ Parser      │   │ FormatSelector │
│ Repository  │   │ PlayerManager  │
└──────┬──────┘   └─────┬──────────┘
       │                │
       │          ┌─────▼─────┐
       │          │  Media3   │
       │          │ ExoPlayer │
       │          └───────────┘
       │
┌──────▼────────────────────────┐
│ Retrofit + OkHttp             │
└───────────────────────────────┘

       ┌────────────────────────┐
       │ Room / DataStore       │
       │ History / Cache / Pref │
       └────────────────────────┘
```

------------------------------------------------------------------------

## 26. Kết luận

Thiết kế nên tập trung vào ba boundary:

1.  **UI ↔ Domain**
2.  **Domain ↔ YouTube/remote data adapter**
3.  **Domain ↔ Playback engine**

`InnerTubeClient` không nên trở thành trung tâm của toàn bộ ứng dụng. Nó
chỉ là một implementation của remote data source.

Tương tự, Media3/ExoPlayer chỉ nên nhận một `PlaybackSource` đã được
domain/playback layer chuẩn hóa.

Thiết kế này giúp:

-   dễ test;
-   dễ thay API;
-   dễ thay player;
-   hạn chế phụ thuộc vào response nội bộ;
-   dễ cache;
-   dễ quản lý playback lifecycle;
-   hỗ trợ PiP;
-   xử lý stream URL expiry;
-   mở rộng sang nguồn video khác trong tương lai.

## Tài liệu tham khảo

-   Android Developers --- Media3 / ExoPlayer supported formats:
    https://developer.android.com/media/media3/exoplayer/supported-formats
-   Android Developers --- HLS with Media3:
    https://developer.android.com/media/media3/exoplayer/hls
-   Android Developers --- DASH with Media3:
    https://developer.android.com/media/media3/exoplayer/dash
-   YouTube API Services --- Developer Policies:
    https://developers.google.com/youtube/terms/developer-policies
-   YouTube API Services --- Required Minimum Functionality:
    https://developers.google.com/youtube/terms/required-minimum-functionality
