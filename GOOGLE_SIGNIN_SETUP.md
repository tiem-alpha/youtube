# Kết nối Google và YouTube

Bản ứng dụng này dùng **Google Identity Services AuthorizationClient** để kết nối tài khoản và lấy access token cho YouTube Data API. Không cần backend, Firebase hoặc Web client ID cho luồng hiện tại. Ứng dụng không hỏi hay lưu mật khẩu Google.

**Tài liệu này dành cho chủ ứng dụng/nhà phát triển.** Chủ ứng dụng cấu hình dự án dùng chung; người dùng cuối chỉ chọn tài khoản và đồng ý quyền, không phải tạo Google Cloud hay API key riêng.

## Kết quả kiểm tra cấu hình ký — 2026-09-05

Đã đối chiếu `:app:signingReport` với `apksigner verify --verbose --print-certs` trên APK hiện có. Hai nguồn khớp nhau; chữ ký APK được xác minh hợp lệ (APK Signature Scheme v2).

| Mục | Giá trị thực tế |
|---|---|
| APK kiểm tra | `app/build/outputs/apk/debug/app-debug.apk` |
| Application ID trong APK | `com.example.app` |
| Phiên bản | `1.1.0` / versionCode `2` |
| Loại chứng chỉ | Android Debug |
| Keystore | `C:\Users\nguye\.android\debug.keystore` |
| Alias | `AndroidDebugKey` |
| Release signing | **Chưa cấu hình** (`Config: null` trong signingReport) |

**SHA-1 của chứng chỉ ký — dùng cho ô SHA-1 khi tạo OAuth client Android:**

```text
63:13:00:F2:2F:3B:CC:C0:DD:52:DA:DB:98:18:0D:0B:BE:C9:41:22
```

**SHA-256 của cùng chứng chỉ ký — lưu để đối chiếu:**

```text
97:27:13:7B:DE:03:AE:25:22:F2:5C:DD:67:DE:A2:19:AB:A1:B1:06:00:CE:F2:B4:CC:D3:0E:E4:F9:9A:A8:B0
```

Đây là fingerprint **chứng chỉ**, không phải hash của toàn bộ file APK hoặc hash của public key. Không dùng kết quả `Get-FileHash app-debug.apk` thay cho SHA-1 này. Trong đầu ra apksigner, đọc dòng **Signer #1 certificate SHA-1 digest**, không đọc dòng **public key SHA-1 digest**.

Google nhận diện OAuth client Android bằng package và SHA-1 của chứng chỉ ký. SHA-256 không thay thế ô SHA-1 này. [Tài liệu Google về xác thực client](https://developers.google.com/android/guides/client-auth)

Chưa đối chiếu được với bản cài trên điện thoại vì tại lần kiểm tra này `adb` không thấy thiết bị. Cũng chưa truy cập Google Cloud của chủ ứng dụng để xác nhận các mục đã tạo trên Console.

## 1. Tạo dự án Google Cloud

1. Mở https://console.cloud.google.com/ bằng tài khoản của bạn.
2. Trong bộ chọn dự án, chọn **New project / Dự án mới**. Đặt tên, ví dụ **Video Companion**.
3. Vào **APIs & Services → Library**; tìm **YouTube Data API v3** và chọn **Enable**.

Giữ đúng dự án này khi tạo OAuth client, cấu hình Audience/Data Access và tạo API key nếu cần.

## 2. Cấu hình Google Auth Platform

1. Mở **Google Auth Platform → Branding** (trên một số giao diện là OAuth consent screen).
2. Điền tên ứng dụng và email hỗ trợ của bạn.
3. Trong **Audience**, chọn **External** nếu dùng tài khoản Google cá nhân.
4. Giữ ứng dụng ở chế độ **Testing** và thêm chính email sẽ đăng nhập trên điện thoại vào **Test users**.
5. Trong **Data Access**, thêm các scope:
   - https://www.googleapis.com/auth/userinfo.email
   - https://www.googleapis.com/auth/userinfo.profile
   - https://www.googleapis.com/auth/youtube.readonly
   - https://www.googleapis.com/auth/youtube.force-ssl

Ứng dụng xin quyền đọc khi kết nối. Quyền ghi chỉ được yêu cầu khi bạn chọn thao tác như thích video, đăng ký kênh, gửi bình luận hoặc sửa playlist. Quyền này cho phép thay đổi dữ liệu YouTube; hãy đọc màn hình đồng ý của Google.

Nếu giao diện chọn scope chưa hiển thị các quyền YouTube, kiểm tra đã bật YouTube Data API v3 trong đúng dự án rồi tìm bằng URL scope đầy đủ. Khai báo scope trong Console không tự cấp quyền; người dùng vẫn phải đồng ý trên màn hình Google.

## 3. Tạo OAuth client Android

Vào **Google Auth Platform → Clients → Create client → Android**:

- Tên: **Video Companion Debug**
- Package name: **com.example.app**
- SHA-1 của bản debug trên máy này:

~~~text
63:13:00:F2:2F:3B:CC:C0:DD:52:DA:DB:98:18:0D:0B:BE:C9:41:22
~~~

SHA-1 này khớp trực tiếp với chứng chỉ của APK debug đã kiểm tra ở đầu tài liệu.

Lưu cấu hình. Không dán Android client ID vào ô API key. Google Play services nhận diện ứng dụng từ package và chứng chỉ ký. Cấu hình Google Cloud có thể cần một lúc để có hiệu lực.

Bản release hoặc bản build trên máy dùng keystore khác cần đăng ký SHA-1 tương ứng.

Không cần điền redirect URI/JavaScript origin cho client loại Android. Trong mã hiện tại không có luồng backend đổi authorization code hoặc xác minh ID token, nên không có bước nhập Web client ID vào ứng dụng. Nếu bổ sung backend hoặc Credential Manager sau này, cần xem lại cấu hình cho luồng mới.

## 4. Đăng nhập trên điện thoại

1. Mở **Video Companion**.
2. Bấm biểu tượng tài khoản ở góc trên.
3. Chọn **Kết nối Google**.
4. Chọn email đã thêm vào Test users và đồng ý quyền được yêu cầu.
5. Mở **Đăng ký** hoặc **Thư viện → YouTube** để kiểm tra dữ liệu.
6. Thích/bỏ thích, đăng bình luận và thay đổi playlist là thao tác thật trên tài khoản; chỉ thử khi bạn muốn thực hiện chúng.

Nếu muốn dùng email khác, chọn **Đăng nhập tài khoản khác** trong hộp thoại tài khoản. Có nút **Thu hồi quyền truy cập Google** và liên kết quản lý quyền trong hộp thoại tài khoản.

## 5. API key tùy chọn cho dữ liệu bổ sung

Từ bản tích hợp NewPipe, trang chủ, tìm kiếm, Shorts và duyệt kênh không cần API key hoặc đăng nhập Google. API key dùng chung chỉ cần cho các dữ liệu còn đọc qua Data API khi chưa đăng nhập, như bình luận và metadata bổ sung. Không cấu hình key vẫn có thể duyệt và phát video công khai. Các tính năng tài khoản tiếp tục cần OAuth.

Phần này cũng dành cho chủ ứng dụng. Ô nhập API key trong bản hiện tại phục vụ cấu hình/kiểm thử; không có nghĩa mỗi người sử dụng phải tạo key. Chủ ứng dụng có thể cấu hình key trong bản build dùng chung. Đăng nhập Google dùng OAuth, không dùng API key để xác thực tài khoản.

Trong cùng dự án, tạo API key tại **APIs & Services → Credentials**. Giới hạn API được phép gọi thành **YouTube Data API v3**; lựa chọn hạn chế ứng dụng phải tương thích với cách gọi REST của bản Android này.

Nhập key trong **Cài đặt → YouTube Data API key → Lưu kết nối**. Cách này không cần build lại. Hoặc đặt vào file local.properties:

~~~properties
YOUTUBE_API_KEY=your-key
~~~

Nếu sửa local.properties, build lại APK. Không commit key vào Git. Key trong APK/thiết bị không phải bí mật có thể bảo vệ tuyệt đối; dùng hạn mức và hạn chế của dự án Google Cloud.

API key chỉ đọc dữ liệu công khai; không thay thế OAuth cho dữ liệu tài khoản.

Khi có key, các yêu cầu công khai dùng key dù người dùng đã đăng nhập hoặc token hết hạn. Kênh đăng ký, playlist cá nhân, video đã thích và thao tác ghi dùng token của tài khoản. Bản debug chưa có key vẫn hỗ trợ token cho dữ liệu công khai để kiểm thử; đó chưa phải chế độ khách đầy đủ.

Sau khi kết nối, ứng dụng gọi `channels.list(part=snippet, mine=true)` để hiển thị tên/ảnh kênh YouTube thay cho hồ sơ Google khi API trả về kênh. Nếu email quản lý nhiều kênh, bản hiện tại dùng kênh được phiên OAuth trả về, chưa có bộ chuyển kênh. [Hướng dẫn kênh mặc định của Google](https://support.google.com/youtube/answer/6019090?hl=en)

## 6. Cho người dùng bên ngoài đăng nhập

Đã xác nhận trên điện thoại ngày 2026-09-08: màn hình Google trả `403: access_denied`, nêu rõ Video Companion đang kiểm thử và chỉ cho người kiểm thử được phê duyệt truy cập. Đây là giới hạn Audience của dự án OAuth; thay APK hoặc API key không gỡ được giới hạn này.

Để mở cho người dùng bên ngoài mà không thêm từng email:

1. Mở [Google Auth Platform → Audience](https://console.cloud.google.com/auth/audience), chọn đúng dự án chứa OAuth client Android của Video Companion.
2. Đặt loại người dùng là **External**. Trong **Publishing status**, chọn **Publish app** để chuyển sang **In production**.
3. Hoàn thiện **Branding**: tên ứng dụng, email hỗ trợ, trang chủ công khai và chính sách quyền riêng tư mô tả đúng cách app dùng dữ liệu. Xác minh quyền sở hữu tên miền dùng cho hồ sơ.
4. Trong **Data Access**, khai báo các scope thực tế: `userinfo.email`, `userinfo.profile`, `youtube.readonly`, `youtube.force-ssl` (URL đầy đủ ở mục 2).
5. Vào **Verification Center**, gửi hồ sơ xác minh theo yêu cầu của Google, gồm giải thích tính năng cần từng quyền và video minh họa luồng xin quyền/sử dụng dữ liệu. Chỉ xin các quyền app thực sự dùng.
6. Khi các quyền cần thiết đã được duyệt, thử lại bằng tài khoản ngoài Test users: đăng nhập, đọc thư viện, rồi kiểm tra luồng xin thêm quyền khi người dùng chọn một thao tác ghi.

**Publish app chưa phải hoàn tất xác minh.** Ứng dụng xin quyền nhạy cảm chưa được duyệt có thể hiện cảnh báo chưa xác minh và bị giới hạn tổng 100 người dùng mới. Tài khoản Workspace vẫn có thể chịu hạn chế của quản trị viên. [Audience và giới hạn người dùng](https://support.google.com/cloud/answer/15549945?hl=en), [Hồ sơ xác minh quyền nhạy cảm](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification).

Trạng thái: đã xác nhận nguyên nhân trên thiết bị; chưa thay đổi cấu hình trong Google Cloud và chưa gửi hồ sơ xác minh.

| Cấu hình | Ai đăng nhập được? |
|---|---|
| External + Testing | Email trong Test users; tối đa 100 người thử |
| External + Production/Published, chưa xác minh | Có thể còn cảnh báo và giới hạn người dùng |
| External + Production/Published, xác minh đầy đủ | Mở cho người dùng bên ngoài; không thêm từng email |
| Internal | Tài khoản trong tổ chức Workspace |

Chỉ bấm **Publish app** không đồng nghĩa đã được xác minh. Với ứng dụng công khai, hoàn thiện Branding, trang chủ, chính sách riêng tư/điều khoản, xác minh tên miền nếu được yêu cầu và gửi xác minh các scope theo Console. Quản trị viên Workspace vẫn có thể chặn truy cập. [Quy định trạng thái OAuth của Google](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview)

Đây là phát hành **OAuth**, khác với phát hành APK lên Google Play.

## 7. Chọn đúng SHA khi phát hành

| Cách phân phối | Chứng chỉ cần đăng ký |
|---|---|
| APK debug hiện tại | SHA-1 debug ở đầu tài liệu |
| APK release tự ký và cài trực tiếp | SHA-1 của keystore ký APK release |
| Cài ứng dụng qua Google Play App Signing | SHA-1 **App signing key certificate** trong Play Console |

Upload key dùng để gửi bản build lên Play có thể khác app signing key dùng ký bản người dùng cài. Không lấy nhầm upload key để cấu hình cho bản cài từ Play. [Google hướng dẫn lấy chứng chỉ](https://developers.google.com/android/guides/client-auth)

Project hiện chưa có signingConfig cho release. Cần chuẩn bị khóa ký phát hành và đăng ký client tương ứng trước khi phân phối release. Nếu đổi `applicationId` khỏi `com.example.app`, đăng ký lại client theo package mới; thay tên hiển thị ứng dụng không làm thay đổi package.

## 8. Tự kiểm tra lại SHA

Chạy từ thư mục project:

```powershell
.\gradlew.bat :app:signingReport
```

Để kiểm tra **APK thực tế**, dùng Android SDK Build Tools (máy hiện tại có bản 36.1.0):

```powershell
$sdkBuildTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.1.0'
& (Join-Path $sdkBuildTools 'apksigner.bat') verify --verbose --print-certs .\app\build\outputs\apk\debug\app-debug.apk
& (Join-Path $sdkBuildTools 'aapt.exe') dump badging .\app\build\outputs\apk\debug\app-debug.apk | Select-Object -First 1
```

Nếu SDK nằm ở nơi khác, sửa `$sdkBuildTools` theo SDK của máy. Đổi keystore, đổi máy build hoặc kiểm tra bản release thì chạy lại; không mặc định dùng SHA debug ghi ở đây.

## Xử lý lỗi

| Biểu hiện | Việc cần kiểm tra |
|---|---|
| Mã đăng nhập 10 | OAuth client phải là Android, đúng package và SHA-1 |
| Debug đăng nhập được, release không được | So sánh chứng chỉ APK release/App signing key với OAuth client |
| Chỉ email của người phát triển đăng nhập được | Kiểm tra Testing/Test users và trạng thái xác minh để phát hành bên ngoài |
| Access blocked / access_denied | Email có trong Test users không, đã cấu hình Audience và scopes chưa |
| API chưa bật | Bật YouTube Data API v3 trong đúng dự án |
| Hết hạn mức | Kiểm tra quota của YouTube Data API; đợi hạn mức được cấp lại hoặc xin tăng |
| Phiên hết hạn | Mở tài khoản → Kết nối lại; Google có thể yêu cầu đồng ý lại |
| Không có dữ liệu kênh/bình luận | Tài khoản có kênh YouTube và có quyền với nội dung đó không |
| Video không cho phát nhúng | Dùng nút Mở YouTube; quyền nhúng do chủ video quyết định |
| INSTALL_FAILED_USER_RESTRICTED | Mở khóa điện thoại, bật Cài đặt qua USB và chấp nhận hộp thoại cài trên điện thoại |

## Giới hạn cần biết

- Quyền OAuth cho Data API không tự đăng nhập tài khoản vào trình phát nhúng.
- Trình phát YouTube giữ nguyên các chức năng và quảng cáo của YouTube. Ứng dụng không chèn quảng cáo riêng.
- Lịch sử/Xem sau/playlist trên máy là dữ liệu của ứng dụng, tách biệt khỏi tài khoản.
- Danh sách video ngắn dùng tìm kiếm/lọc độ dài, không phải feed Shorts cá nhân hóa của YouTube.
- Đã quan sát đăng nhập trên điện thoại phát triển; chưa xác nhận OAuth đã mở Production/xác minh cho người dùng ngoài Test users.
- Phát hành công khai cần hoàn thiện hồ sơ ứng dụng, chính sách riêng tư và các bước xét duyệt OAuth mà Google yêu cầu cho dự án.

Tài liệu chính thức: [Authorization trên Android](https://developer.android.com/identity/authorization), [YouTube Data API](https://developers.google.com/youtube/v3), [Yêu cầu trình phát nhúng](https://developers.google.com/youtube/terms/required-minimum-functionality).
