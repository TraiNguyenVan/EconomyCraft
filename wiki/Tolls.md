# Quản lý Trạm Thu Phí (Tolls)

Nhìn vào một block trong vòng năm block, rồi mở `/eco` → **Tolls**. Nút này yêu cầu quyền
`economycraft.command.toll`. Hãy tiếp tục nhìn vào block đó trong khi dùng menu; nếu mục tiêu,
chiều không gian, quyền hạn hoặc chủ sở hữu thay đổi giữa chừng, bước xác nhận sẽ từ chối.

- Với một block chưa được đăng ký, menu hiện **Create** (Tạo), dùng trình chỉnh số tiền để đặt mức phí thuần.
- Với trạm của chính bạn, menu hiện **Info**, **Change fee**, **Transfer** và **Remove**.
- Với trạm của người chơi khác, menu chỉ hiện **Info**. Những người chơi đạt cùng điều kiện quản trị với
  nút **Admin** trong menu chính còn thấy thêm **Admin transfer** và **Admin remove**.

Việc tạo và đổi phí dùng chung các kiểm tra sửa block và chế độ chơi của lệnh. Việc tạo tuân thủ
`max_active_tolls_per_player`; khi chuyển nhượng, giới hạn của người nhận được kiểm tra lại ở bước xác nhận.
Giá trị `0` nghĩa là không giới hạn. Danh sách chọn người chơi liệt kê người đang online và các tài khoản
EconomyCraft đã biết, bao gồm cả người chơi offline có tài khoản đã lưu. Danh sách không tự tạo UUID từ
một tên chưa được xác minh, nên không thể vô tình tạo ra một danh tính người chơi mới chỉ vì gõ sai chính tả.
Quản trị viên có thể chuyển trạm cho chính mình; chuyển cho chủ sở hữu hiện tại sẽ bị từ chối.

Việc chuyển nhượng và gỡ bỏ đều cần xác nhận. Bấm Hủy sẽ trở về mà không thay đổi gì. Các thao tác quản trị sẽ
thông báo cho chủ sở hữu cũ nếu họ đang online. Quyền quản trị không cho phép sửa phí hay bỏ qua kiểm tra khi
tạo trạm, và các lệnh trạm thu phí vẫn giữ nguyên kiểm tra chủ sở hữu.

Thay đổi được lưu qua kho `tolls.json` hiện có. Cách xử lý thanh toán, thuế, thời gian chờ, chuột phải và bản
thả trọng lực không thay đổi. Không cần mod client và không cần di chuyển dữ liệu.

> [!NOTE]
> Các tính năng liên quan đến claim đất cần **ShopGuard**. ShopGuard chỉ chạy trên Fabric. Nếu server NeoForge
> hoặc server Fabric không cài ShopGuard, các tính năng sau sẽ không hoạt động: giảm 50% chi phí claim và
> tăng sát thương trong vùng đất của phe Monarchy, cùng tăng tốc độ trên vùng hoang dã của phe Anarchism.

## Hành vi với rương chứa

- Trạm thu phí được gắn vào một vị trí block duy nhất. Một rương kép gồm hai block, nhưng EconomyCraft gộp
  cả hai nửa về cùng một trạm. Tương tác với bất kỳ nửa nào cũng dùng chung một mức phí và chỉ tính là một
  trạm đang hoạt động.
- Một phễu (hopper) đặt ngay bên dưới rương có trạm thu phí không thể lấy vật phẩm ra. Điều này chặn cách
  vượt trạm thu phí bằng tự động hóa; người chơi tương tác bình thường vẫn phải trả phí.

## Hiển thị phí

Mức phí hiện tại của trạm thu phí cũng xuất hiện trên **action bar** mặc định của Minecraft khi tâm ngắm
đang nằm trên block trạm thu phí trong vòng năm block. Khi người chơi nhìn đi chỗ khác, thông báo ngừng được
làm mới và tự mờ dần.
