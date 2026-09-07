# Website Video Companion

Đã xuất bản lên GitHub Pages ngày 08/09/2026. Mã website công khai nằm trên nhánh [`gh-pages`](https://github.com/tiem-alpha/youtube/tree/gh-pages). Thư mục `website/` trong workspace là bản nguồn tương ứng.

## Đường dẫn cho Google Auth Platform

| Trường | Giá trị |
|---|---|
| Application home page | https://tiem-alpha.github.io/youtube/ |
| Application privacy policy link | https://tiem-alpha.github.io/youtube/privacy/ |
| Application terms of service link | https://tiem-alpha.github.io/youtube/terms/ |
| Authorized domains | `tiem-alpha.github.io` |

Site không cần backend, đăng nhập, JavaScript hoặc công cụ build. Email hỗ trợ công khai: `nguyentiem21101998@gmail.com`.

## Xác minh Google

1. Dùng tài khoản có quyền Owner/Editor của dự án OAuth để mở Google Search Console.
2. Thêm thuộc tính **URL prefix**: `https://tiem-alpha.github.io/youtube/`.
3. Chọn xác minh bằng **HTML tag** và lấy thẻ `google-site-verification` Google cấp. Thêm nguyên thẻ vào phần `head` của trang chủ trên nhánh `gh-pages`; không điền mã giả. Có thể dùng tệp HTML xác minh do Google cấp thay cho thẻ.
4. Chờ GitHub Pages triển khai xong, quay lại Search Console và bấm xác minh. Giữ thẻ/tệp trên website sau khi thành công.
5. Điền các URL trên vào Branding. Cấu hình Audience và gửi xác minh quyền YouTube theo `GOOGLE_SIGNIN_SETUP.md`.

Website hoạt động không có nghĩa Google đã xác minh quyền sở hữu, chấp nhận Authorized domains hoặc duyệt OAuth. Nếu Console không chấp nhận tên miền con này, đối chiếu yêu cầu của Console và dùng tên miền riêng nếu được yêu cầu; không khai báo `github.io` là tên miền mình sở hữu.

Nguồn: [Xác minh Search Console](https://support.google.com/webmasters/answer/9008080?hl=en), [Xác minh quyền nhạy cảm OAuth](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification).

## Cập nhật website

Clone riêng nhánh `gh-pages`, sửa HTML/CSS, commit rồi push nhánh đó. GitHub Pages tự build từ nhánh `gh-pages` ở thư mục gốc.

Đã kiểm tra: liên kết tương đối của cả ba trang, một tiêu đề H1 mỗi trang, giao diện desktop, GitHub Pages deployment thành công và cả ba URL trả HTTP 200.
