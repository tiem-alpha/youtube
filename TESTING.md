# Trạng thái kiểm chứng — 2026-09-05

Bản: **1.1.0**, versionCode **2**, package **com.example.app**.

## Sửa tạm dừng khi chuyển toàn màn hình / khóa máy — 2026-09-07

- Chặn window visibility ở bước dispatch của BackgroundPlaybackWebView để các view con cũng được giữ trạng thái. Bọc custom view toàn màn hình của Chromium bằng BackgroundPlaybackLayout với cùng quy tắc; trước đây custom view nằm trực tiếp trong Dialog nên bỏ qua xử lý phát nền của WebView.
- Khi vào/ra toàn màn hình, khôi phục phát nếu trạng thái trước khi chuyển là playing/buffering. Không khôi phục video đã pause hoặc khi màn hình xem bị dispose.
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` qua; APK đã cài cập nhật thành công trên điện thoại. BackgroundPlaybackVisibilityTest: **4/4 qua**, kiểm tra truyền visibility đến view con cho cả khung thường/toàn màn hình và khi tắt phát nền.
- WebPlaybackServiceTest chưa chạy xong: MIUI báo `Abort background activity starts` khi ActivityScenario mở Activity; đã dừng phiên test bị kẹt. Không tính bài này là đã qua.
- Chưa xác nhận end-to-end nút toàn màn hình YouTube và khóa máy: ADB bị chặn INJECT_EVENTS, sau đó thiết bị ngắt kết nối. Cần thử video dài: bật/tắt toàn màn hình nhiều lần khi đang phát và khi pause; khóa máy ở cả hai chế độ ít nhất 5 phút; kiểm tra play/pause từ màn hình khóa và mở lại giữ tiến độ.

## Cập nhật 2026-09-07: phục hồi, ẩn video và làm mới

### Sửa cuộn popup bị kéo thu nhỏ video

- HoldToMinimizeLayout tôn trọng quyền giữ cử chỉ của WebView và giữ quyền đó đến hết lần chạm. Nhận diện popup trong iframe qua document-start script, chặn thu nhỏ khi menu mở và khôi phục cho lần vuốt mới sau khi đóng menu. WebView không hỗ trợ thì dùng thanh tiêu đề/nút thu nhỏ.
- 43 unit test, build APK và lint qua. 10 instrumentation test thuộc HoldToMinimizeTest và PlayerGestureGuardTest đã chạy thành công trên M2102J2SC / Android 13. Test WebView dùng HTML fixture trong iframe khác origin, kiểm tra mở menu/menu con chặn thu nhỏ và đóng menu khôi phục kéo; không thay thế xác nhận thủ công với menu YouTube trực tiếp.
- Cài APK sau kiểm thử bị điện thoại từ chối: INSTALL_FAILED_USER_RESTRICTED (Install canceled by user). APK mới nằm tại app/build/outputs/apk/debug/app-debug.apk.

- Thêm unit test lưu/khôi phục danh sách ẩn, tương thích dữ liệu cũ, và phân trang làm mới không trả lại video cũ/đã ẩn.
- Bổ sung instrumentation test cho thứ tự đóng phiên cũ trước khi mở phiên mới; phải chờ qua thời gian bàn giao mà dịch vụ vẫn giữ phiên mới.
- Cần kiểm tra trên điện thoại: khóa màn hình khi video vừa tải; phát nền rồi mở lại và đổi video nhiều lần; mất mạng rồi nối lại; pause không tự phát lại; thử lại giữ vị trí; loop không chuyển sang video kế tiếp.
- Kiểm tra giao diện: bốn mục cài đặt, thay đổi tốc độ, ẩn ở Home/video cùng chủ đề/Shorts, khởi động lại vẫn ẩn; làm mới không trùng danh sách trước. Transcript hiện mở YouTube, chất lượng chọn bằng menu trong khung YouTube.
- ADB không có thiết bị kết nối trong lần kiểm tra này; chưa chạy instrumentation hoặc xác nhận phát khi khóa màn hình trên máy thật.

## Sửa luồng YouTube xuống nền

- Xác nhận trong mã: YouTubePlayer gọi pauseVideo và WebView.onPause tại ON_PAUSE, nên bản sửa wake lock cho Media3 trước đó không tác động đến video YouTube.
- Màn hình Watch bật luồng nền riêng: bỏ pause khi Activity xuống nền, giữ thông báo window visibility của WebView, gửi trạng thái thật từ IFrame sang foreground media service. Thông báo và media session có play/pause/stop/seek; rút tai nghe gửi pause; đóng màn hình xem kết thúc dịch vụ. Shorts giữ hành vi dừng khi xuống nền.
- Wake lock có timeout, Wi-Fi lock được giải phóng khi dừng/tạm dừng; watchdog kết thúc dịch vụ khi mất heartbeat 60 giây, paused timeout 5 phút. Khi dịch vụ kết thúc, yêu cầu WebView dừng để tránh phát không có dịch vụ.
- 36 unit test, lint và assembleDebug qua. WebPlaybackServiceTest bổ sung kiểm tra điều khiển media session và bỏ qua yêu cầu stop của phiên cũ; đây là test dịch vụ, không chứng minh phát YouTube khi tắt màn hình.
- ADB báo thiết bị offline; reconnect offline không tìm thấy thiết bị. Chưa cài bản mới và chưa chạy thử khóa màn hình/Home/điều khiển thông báo trên điện thoại. Cần kiểm tra video thật liên tục ít nhất 5 phút khi khóa máy, pause/play/seek từ màn hình khóa, cuộc gọi/ứng dụng audio khác, rút tai nghe và đóng màn hình xem.

## Cập nhật Home theo sở thích và bộ sưu tập tài khoản

- Home lập nguồn gợi ý từ chủ đề/kênh trong lịch sử trên thiết bị, video đã thích và kênh đăng ký. Trộn tối đa 3 nguồn, loại video đã xem/đã thích và video trùng; giữ phân trang riêng, giữ nguồn bị lỗi để thử lại. Nếu toàn bộ nguồn gợi ý lỗi, trang đầu thử video phổ biến.
- Thư viện mặc định mở bộ sưu tập tài khoản, tự tải khi kết nối/mở tab, làm mới và tải thêm. Dữ liệu trong bộ nhớ được xóa khi đổi tài khoản/đăng xuất; phản hồi từ tài khoản cũ không ghi vào thư viện mới. Có lối vào video đã thích và mở playlist đã lưu trên YouTube.
- 36 unit test qua (10 bài mới), Lint 0 lỗi; assembleDebug và assembleDebugAndroidTest thành công. Các bài mới kiểm tra lựa chọn sở thích, trộn/lọc/phân trang, lỗi một phần/toàn bộ, hủy tác vụ, lưu category tương thích dữ liệu cũ và request OAuth cho bộ sưu tập.
- AppNavigationTest đã cập nhật cho tab mặc định mới; chỉ build APK test, chưa chạy instrumentation trên thiết bị cho thay đổi này.
- Chưa kiểm chứng end-to-end với tài khoản thật. Cần kiểm tra: mở Home trước/sau đăng nhập; xem nhiều video cùng chủ đề rồi quay lại Home; tải thêm không trùng; mở Thư viện có playlist riêng tư, tải trang tiếp, sửa playlist rồi quay lại; đăng xuất/đổi tài khoản không còn bộ sưu tập cũ; mất mạng/hết phiên có thể thử lại.
- Giới hạn: không nhập lịch sử xem/feed Home của youtube.com; chỉ liệt kê playlist tài khoản sở hữu, không phải mọi playlist người khác đã lưu.

## Cập nhật phát media khi tắt màn hình

- Thêm quyền WAKE_LOCK và cấu hình Media3 WAKE_MODE_NETWORK để giữ CPU/Wi-Fi trong lúc phát file/URL media riêng.
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` thành công; `git diff --check` qua.
- Chưa kiểm thử khóa màn hình trên thiết bị cho thay đổi này. Video YouTube nhúng vẫn dừng khi ứng dụng vào nền.
- Kiểm thử thủ công: phát file âm thanh/video dài, khóa màn hình ít nhất 5 phút và xác nhận âm thanh liên tục; thử lại với URL HTTPS qua Wi-Fi. Kiểm tra pause/play từ màn hình khóa, mở lại ứng dụng giữ vị trí, rút tai nghe và hẹn giờ dừng. Khi dừng hoặc hết media, kiểm tra dịch vụ không giữ wake lock.

## Cập nhật chế độ khách và danh tính kênh

- 26 unit test qua; Lint 0 lỗi; assembleDebug và cài cập nhật APK lên điện thoại thành công.
- Test HTTP mới xác nhận trang chủ/tìm kiếm/Shorts/kênh dùng cùng app key trước/sau đăng nhập, metadata không dùng token hết hạn, playlist riêng vẫn dùng OAuth, lỗi yêu cầu công khai không xóa phiên Google và tên/ảnh kênh được đọc từ channels.list(mine=true).
- Phiên đăng nhập/đăng xuất giữ danh sách công khai; cache dữ liệu cá nhân bị xóa. Khôi phục phiên lúc mở app chỉ chạy im lặng, không tự mở hộp thoại consent.
- Chưa kiểm chứng tên kênh thật sau đăng nhập trong bản này. Kênh được lấy qua phiên OAuth; chưa có bộ chuyển nhiều kênh.
- Chưa có YOUTUBE_API_KEY trong cấu hình build tại lần kiểm thử; chưa xác nhận Google OAuth Production/verification. Vì vậy chưa thể xác nhận duyệt danh sách ở chế độ khách trên mạng thật hoặc mọi tài khoản ngoài Test users đăng nhập được.

## Cập nhật sửa trình phát và bình luận

- Đã quan sát qua WebView DevTools trên điện thoại: phần `body` của trang nhúng có chiều cao 0 trong khung WebView 1080 × 660; trình phát vẫn được tạo. Đây là lỗi khiến hình và điều khiển bị ẩn.
- Đã đặt WebView dùng MATCH_PARENT, trang HTML dùng chiều cao viewport và iframe phủ kín khung; bật rõ controls/fullscreen.
- Bình luận tải độc lập với thông tin video; thêm nút Bình luận ngay dưới player và hủy tác vụ màn hình cũ khi chuyển video.
- Bình luận trên máy thật trả lỗi thiếu quyền. Google Discovery khai báo cả commentThreads.list và comments.list dùng scope youtube.force-ssl; đăng nhập ban đầu chỉ có youtube.readonly. Đã thêm luồng cấp quyền theo thao tác rồi tải lại; nếu có API key thì đọc bình luận công khai bằng key, tránh dùng token chỉ có quyền đọc video.
- Bản cuối: 20 unit test qua, Lint 0 lỗi, build debug và cài cập nhật lên điện thoại thành công. Test bổ sung kiểm tra ưu tiên API key cho bình luận/phản hồi và lỗi token thiếu scope.
- Đã cài cập nhật APK lên điện thoại thành công bằng `adb install -r`. Các ghi chú chặn cài đặt bên dưới là kết quả của lần kiểm thử trước.
- Đã xác nhận sau cài đặt: body và iframe đều cao 240 CSS px, trạng thái player là đang phát; ảnh chụp màn hình điện thoại đã có hình video.
- Chưa xác nhận thao tác chạm pause/tốc độ/phụ đề/chất lượng và bình luận thật sau bản sửa; điện thoại chặn lệnh ADB mô phỏng chạm bằng INJECT_EVENTS.

## Đã kiểm chứng trên máy phát triển

- Build APK debug thành công.
- Build APK instrumentation test thành công.
- 16 unit test chạy qua: 8 parser/link/library, 5 HTTP repository, 2 timer và 1 test mẫu có sẵn.
- HTTP repository test dùng server loopback trong bài test; kiểm tra phân trang, mã hóa từ khóa Unicode, Bearer token, chặn ghi khi thiếu phiên, lỗi 401 và bảo toàn mô tả/ngôn ngữ khi đổi tên playlist. Không gọi hoặc sửa tài khoản YouTube thật.
- Android Lint: 0 lỗi; vẫn có cảnh báo (phiên bản thư viện/SDK, gợi ý KTX, PiP và tài nguyên mẫu).
- Git diff whitespace check qua.

## Chưa kiểm chứng thành công

- Cài APK chính và APK test trên điện thoại: Android trả **INSTALL_FAILED_USER_RESTRICTED: Install canceled by user**. Không có kết quả instrumentation test chạy thành công để báo cáo.
- Không có AVD/system image Android sẵn trên máy để chạy thay thế.
- Đăng nhập/cấp quyền Google và các thao tác YouTube thật: chủ dự án xác nhận chưa có Google Cloud/OAuth.
- Phát video YouTube qua mạng, toàn màn hình, PiP và phát nền trên phần cứng: chưa xác nhận end-to-end do chặn cài đặt.

## Bài kiểm thử thiết bị đã chuẩn bị

- AppNavigationTest: thư viện → playlist → media trên máy → cài đặt → quay lại.
- LibraryPersistenceTest: lưu/đọc lại lịch sử, xem sau, playlist; loại trùng và xóa.
- PlaybackIntegrationTest: phát WAV im lặng sinh trong bài test, theo dõi tiến độ, seek, hẹn giờ, hủy hẹn giờ và pause.
- ExampleInstrumentedTest: test package mẫu có sẵn.

## Thực hiện sau khi bật quyền cài đặt

Mở khóa điện thoại, bật USB debugging và Cài đặt qua USB nếu nhà sản xuất yêu cầu, rồi chấp nhận hộp thoại cài đặt trên máy.

~~~powershell
.\scripts\build-and-install.ps1
~~~

Để chạy instrumentation, dùng thiết bị kiểm thử và giữ bản sao dữ liệu cần thiết vì Gradle có thể cài/gỡ ứng dụng trong vòng đời test:

~~~powershell
.\gradlew.bat :app:connectedDebugAndroidTest
~~~

Đọc [hướng dẫn Google Cloud](GOOGLE_SIGNIN_SETUP.md), kết nối tài khoản và kiểm tra trang Đăng ký/Playlist. Các thao tác like/comment/subscribe/playlist là thay đổi thật, chỉ thực hiện khi chủ tài khoản chọn.
