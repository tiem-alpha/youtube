# Video Companion

Ứng dụng Android Kotlin / Jetpack Compose để duyệt YouTube, sử dụng thư viện cá nhân và phát media trên thiết bị.

## Giao diện và phát video

- Trong từng video, bấm bánh răng **Cài đặt video → Hẹn giờ tắt · Duration** để chọn **30 / 60 / 90 / 120 phút** hoặc hủy. Đồng hồ đếm ngược hiện cạnh bánh răng. Hẹn giờ chạy trong dịch vụ khi đang phát, giữ khi thu nhỏ/đổi video và hủy khi đóng video.
- Kéo xuống trên thanh tiêu đề trình phát hoặc bấm mũi tên xuống để quay lại danh sách. Video tiếp tục phát ở cuối màn hình; bấm mũi tên lên để mở rộng. Trình phát nhúng giữ chiều cao tối thiểu 200 dp theo [yêu cầu IFrame của YouTube](https://developers.google.com/youtube/iframe_api_reference).
- Bấm biểu tượng tìm kiếm để mở ô nhập; biểu tượng micro mở nhận dạng giọng nói của Android. Thiết bị cần có dịch vụ nhận dạng giọng nói.
- Home là danh sách native với tông xanh lam nhạt, không tải trang YouTube/WebView. Gợi ý riêng của app dùng lịch sử xem ghi nhận theo tài khoản trong app và video đã thích: tối đa hai truy vấn chủ đề từ tiêu đề (dự phòng bằng danh mục), một kênh thường xem gần đây, hai kênh đăng ký và một nguồn phổ biến tại Việt Nam. Kết quả được xen kẽ, loại trùng và bỏ video đã xem/đã thích. Tìm kiếm thông thường vẫn giữ thứ tự Data API.
- Kết nối Google bằng biểu tượng tài khoản của app để thêm video đã thích và kênh đăng ký. Thuật toán chỉ xét tối đa 200 kênh đăng ký, xoay vòng kênh mỗi cửa sổ 10 phút; tối đa sáu nguồn video mỗi trang. Đây không phải thuật toán hoặc feed HOME chính thức của YouTube. API không cung cấp lịch sử xem YouTube bên ngoài app ([thay đổi API](https://developers.google.com/youtube/v3/revision_history)). Lịch sử dùng cho gợi ý bắt đầu được ghi riêng cho từng tài khoản từ bản này; lịch sử trên máy trước đó vẫn nằm trong Thư viện.
- Thanh thông báo nhận trạng thái đang phát/tạm dừng/đang tải/phát hết, vị trí và tổng thời lượng từ trình phát.

Màn hình xem hiển thị avatar kênh và một bình luận xem trước (tối đa hai dòng). Bấm **Xem thêm bình luận** để đọc đầy đủ, tải tiếp hoặc viết bình luận; bấm **Thu gọn** hoặc Back để quay lại thông tin/video cùng chủ đề. Video cùng chủ đề tự tải bằng tìm kiếm tiêu đề qua Data API, giữ thứ tự kết quả và bỏ video đang xem; đây không phải danh sách gợi ý cá nhân của YouTube.

Giữ trên vùng video rồi kéo xuống ít nhất 64dp và thả để thu nhỏ; trình phát di chuyển theo ngón tay và giữ nguyên phiên phát. Chạm nhanh và kéo tua ngang vẫn chuyển đến trình phát. Có thể kéo thanh tiêu đề hoặc dùng nút thu nhỏ như trước. Cơ chế nhận thao tác dựa trên [xử lý touch của Android](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup).

Bản sửa đã build và chạy unit test/lint; thao tác WebView, kéo thu nhỏ, giọng nói, timer khi khóa màn hình và thông báo vẫn cần kiểm thử trên Android thật.

## Cache và bộ đệm

- Danh sách công khai lưu trong cache máy tối đa 4 MB. Dùng lại trong 10 phút; dữ liệu cũ tối đa 24 giờ được hiện trước rồi cập nhật. Khi mất mạng vẫn giữ danh sách đã có. Không lưu cache đĩa cho playlist tài khoản, video đã thích hoặc tìm livestream. Nút **Làm mới** bỏ qua cache để lấy dữ liệu mới.
- Ảnh có cache RAM 12 MB và cache đĩa 48 MB, hạn lưu 7 ngày. Android có thể tự thu hồi cache khi thiếu dung lượng.
- Home có cache riêng tối đa 4 MB, khóa theo ID tài khoản hoặc khách; giữ cả con trỏ phân trang từng nguồn. Dùng lại trong 10 phút, hiển thị dữ liệu cũ tối đa 24 giờ trong lúc cập nhật. Đổi tài khoản xóa cache RAM để không hiển thị gợi ý của tài khoản trước. **Làm mới** cập nhật cả tín hiệu tài khoản và danh sách; xóa lịch sử cũng xóa lịch sử gợi ý và cache Home liên quan. Khi chưa có tín hiệu, Home dùng video phổ biến; khi lỗi mạng vẫn giữ danh sách đã tải kèm nút thử lại.
- Media trực tiếp dùng [Media3 LoadControl](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl.Builder): mục tiêu buffer 30–90 giây, bắt đầu sau 1,5 giây, phục hồi sau khi cạn đệm với 5 giây dữ liệu, giữ 15 giây đã phát để tua lại. Ngưỡng dung lượng 64 MB được ưu tiên nên video bitrate cao có thể không đạt đủ 90 giây; đây không phải giới hạn tổng RAM ứng dụng.
- Các thông số Media3 không áp dụng cho video nhúng YouTube. Buffer và chất lượng thích ứng của YouTube do trình phát YouTube quản lý. Giữ nguyên player khi thu nhỏ giúp tránh tải lại video, nhưng không bảo đảm loại bỏ giật do mạng hoặc thiết bị.

## Chạy dự án

Mở bằng Android Studio, cài Android SDK tương ứng cấu hình Gradle và đồng bộ dependencies.

~~~powershell
.\gradlew.bat :app:assembleDebug
~~~

APK: **app/build/outputs/apk/debug/app-debug.apk**.

## Phát khi tắt màn hình

Trong **Media trên máy**, chọn file hoặc phát URL HTTPS trực tiếp rồi khóa màn hình. Dịch vụ Media3 tiếp tục phát âm thanh và cung cấp điều khiển media trên màn hình khóa. Trình phát giữ CPU/Wi-Fi khi cần cho việc phát; tạm dừng, dừng hoặc phát hết sẽ giải phóng các khóa này. Hẹn giờ dừng vẫn chạy trong dịch vụ.

Màn hình xem YouTube đã bổ sung luồng phát nền: không gọi pause khi Activity xuống nền, giữ WebView khi khóa màn hình và chạy foreground media service với điều khiển phát/tạm dừng/dừng. Dịch vụ giải phóng CPU/Wi-Fi khi tạm dừng, tự kết thúc sau 5 phút tạm dừng hoặc 60 giây không nhận trạng thái khi đang phát. Thu nhỏ hoặc quay lại danh sách giữ trình phát; đóng video hoặc vuốt đóng ứng dụng kết thúc phiên này. Shorts vẫn dừng khi xuống nền. Chưa xác nhận phát YouTube khi khóa màn hình trên thiết bị thật cho bản sửa này; hoạt động thực tế còn phụ thuộc WebView/trình phát YouTube.

Cài trên thiết bị Android đã cho phép USB debugging và cài qua USB:

~~~powershell
.\scripts\build-and-install.ps1
~~~

Đọc [GOOGLE_SIGNIN_SETUP.md](GOOGLE_SIGNIN_SETUP.md) để tạo Google Cloud/OAuth. Không có OAuth/API key vẫn có thể mở liên kết YouTube bằng nút liên kết trên thanh công cụ và phát file/URL media riêng.

Cấu hình Cloud là việc của **chủ ứng dụng**, không phải từng người dùng. Hướng dẫn đã có SHA-1/SHA-256 đối chiếu với APK 1.1.0, phân biệt debug/release/Play App Signing và cách chuyển từ thử nghiệm sang đăng nhập công khai. Bản release hiện chưa cấu hình khóa ký.

## Phần đã triển khai

Xem video nhúng không yêu cầu đăng nhập. Trang chủ/tìm kiếm/bình luận công khai ưu tiên API key dùng chung của ứng dụng; chủ ứng dụng cần cấu hình key trước khi phát hành chế độ khách đầy đủ. Đăng nhập phục vụ dữ liệu cá nhân và tương tác, đồng thời lấy tên/ảnh kênh YouTube tương ứng. Tự nối lại phiên khi khởi động không mở hộp thoại cấp quyền; người dùng chủ động bấm kết nối khi cần.

- Trang chủ lấy trực tiếp giao diện và danh sách gợi ý của YouTube qua WebView; tìm kiếm native theo từ khóa, chủ đề, ngày, độ dài và video đang trực tiếp.
- Thumbnail, tiêu đề, kênh, lượt xem/ngày đăng khi API cung cấp.
- Trình phát YouTube nhúng với nút điều khiển chính thức, toàn màn hình, nhớ vị trí xem, video tiếp theo và tùy chọn tự phát.
- Danh sách video ngắn vuốt dọc; chỉ tạo player cho trang đang hiển thị.
- Kết nối Google bằng AuthorizationClient, quyền đọc/ghi theo thao tác, xử lý thiếu quyền và hết phiên, đăng xuất và thu hồi quyền.
- Danh sách đăng ký, trang kênh, đăng ký/hủy đăng ký, thích/không thích/bỏ đánh giá.
- Bình luận và phản hồi: đọc phân trang, gửi bình luận/phản hồi.
- Thư viện mở mặc định bộ sưu tập tài khoản, tự tải playlist khi kết nối và khi mở tab, có làm mới/tải thêm; hiển thị danh tính kênh và lối vào video đã thích. Playlist YouTube: tạo với quyền riêng tư, xem nội dung, thêm/bỏ video, đổi tên, xóa. Dữ liệu tài khoản trong bộ nhớ được xóa khi đổi tài khoản/đăng xuất.
- Video đã thích trên YouTube.
- Lịch sử, vị trí xem, xem sau, playlist và lịch sử tìm kiếm lưu trên thiết bị; quản lý/xóa dữ liệu.
- Chia sẻ video và nhận liên kết từ Android Sharesheet.
- Trình phát Media3 có hình cho file/URL HTTPS trực tiếp, âm thanh nền, seek/tốc độ, phụ đề từ nguồn hỗ trợ, toàn màn hình, PiP và hẹn giờ dừng trong dịch vụ.
- Giao diện tiếng Việt, sáng/tối, cấu hình API key từ ứng dụng, thông tin riêng tư.

Các chức năng tài khoản có mã API thật nhưng còn cần kiểm thử end-to-end sau khi chủ dự án cấu hình OAuth. Việc build thành công không chứng minh mọi video/thiết bị/tài khoản đều hoạt động.

## Phạm vi và phần chưa tương đương YouTube

Ứng dụng không phải bản sao hoàn chỉnh của nền tảng YouTube. Chưa có hệ thống đề xuất cá nhân hóa của YouTube, feed Shorts chính thức, thông báo tài khoản/push, Cast, tải offline YouTube/Premium, hội viên/thanh toán, công cụ tạo nội dung và phát livestream trong ứng dụng. Cài đặt chất lượng/phụ đề/tốc độ video YouTube do trình phát nhúng cung cấp, tùy khả năng của video và thiết bị.

Home dùng phiên web riêng, không nhập lịch sử xem hoặc cookie từ ứng dụng YouTube. Lịch sử trên thiết bị dùng chung cho ứng dụng. `playlists.list(mine=true)` tải playlist do tài khoản sở hữu, không liệt kê toàn bộ playlist của người khác đã lưu; Thư viện có nút mở danh sách đó trên YouTube. Xem sau và lịch sử trong ứng dụng vẫn là dữ liệu trên thiết bị.

Không có SDK quảng cáo của ứng dụng. Không chặn quảng cáo của trình phát YouTube; không thể cam kết mọi video YouTube không quảng cáo. Trình phát media riêng không chèn quảng cáo.

**android_video_client_technical_design.md** là tài liệu định hướng ban đầu, không phải danh sách tính năng đã hoàn tất; implementation hiện sử dụng Data API chính thức và trình phát nhúng, không sử dụng InnerTube.

## Kiểm thử

~~~powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
~~~

Unit test kiểm tra link/ID, từ chối URL giả mạo, parser API, lỗi không lộ dữ liệu phản hồi, lưu/khôi phục thư viện và hẹn giờ.

Instrumentation test kiểm tra điều hướng, lưu thư viện bền vững và phát/seek/pause/timer của media service với WAV im lặng sinh trong bài test. Cần thiết bị cho phép cài cả app và APK test. Gradle connected tests có thể cài lại hoặc gỡ app trong vòng đời kiểm thử; chỉ chạy trên thiết bị kiểm thử và giữ bản sao dữ liệu cần thiết.

## Cấu trúc chính

- **domain**: model và phân tích liên kết.
- **data**: HTTP adapter YouTube, parser/error mapping, thư viện thiết bị.
- **ui**: ViewModel, xác thực Google, màn hình nội dung/thư viện và các player view.
- **playback**: MediaController, foreground media session và timer.
- **MainActivity.kt**: điều hướng và giao diện ứng dụng.

Access token được giữ trong bộ nhớ, không ghi log hoặc SharedPreferences. Cấu hình kết nối bị loại khỏi Android backup; thư viện thiết bị có thể được sao lưu theo Android. Các sửa đổi trên YouTube chỉ thực hiện qua nút người dùng chọn; thao tác xóa playlist có xác nhận trong ứng dụng.
