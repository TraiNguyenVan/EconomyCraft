# Chi tiết Phe phái (Factions)

EconomyCraft mang đến 4 phe phái với các lợi ích (Buff) và trách nhiệm (Debuff) đặc thù. Bạn có thể chọn phe bằng lệnh `/tag` hoặc `/eco party <tên_phe>`.

> [!NOTE]
> Quy ước **"X phút online"**: Thời gian chỉ được tích lũy khi người chơi trực tiếp online trên server và tạm dừng khi offline. Đồng hồ đếm chỉ kích hoạt và đặt lại khi người chơi tích lũy đủ X phút online.

---

## 1. Communism (Chủ nghĩa cộng sản)
- **Biểu tượng:** `☭` | **Màu sắc:** Đỏ
- **Buff:**
  - **Tài trợ:** Các công trình công cộng của phe Communism (thông tin mô tả danh dự cho thợ xây).
  - **Đầu tư công:** Có 50% tỉ lệ được miễn hoàn toàn tiền thuế (tax) khi chi trả qua các trạm thu phí Toll (chủ sở hữu trạm thu phí vẫn nhận đủ số tiền phí).
- **Debuff:**
  - **Đảng phí:** Mỗi 45 phút tích lũy online, người chơi bị trừ $10 nộp vào Đảng phí (tiền bị thiêu hủy khỏi lưu thông).
  - **Thuế thu nhập cá nhân:** Mỗi 45 phút online, ngay sau khi thu Đảng phí, tiến hành đánh thuế chống đầu cơ dựa trên số dư còn lại:
    - Số dư trên $10,000: Thu 0.5% tổng số tiền hiện có.
    - Số dư trên $15,000: Thu 0.75% tổng số tiền hiện có.
    - Số dư trên $22,000: Thu 1.25% tổng số tiền hiện có.

---

## 2. Capitalism (Chủ nghĩa tư bản)
- **Biểu tượng:** `$` | **Màu sắc:** Vàng
- **Buff:**
  - **Thị trường cạnh tranh:** Khi bạn đăng bán đồ trên chợ `/ah`, người mua đồ của bạn sẽ được miễn phí thuế giao dịch, giúp mặt hàng của bạn cạnh tranh tốt hơn về giá.
- **Debuff:**
  - **Nhà nước tư bản:** Thuế tài sản hàng ngày ở mức 5% (nhân với hệ số lạm phát và tỉ trọng tài sản của phe). Thuế giao dịch qua trạm thu phí Toll tăng thêm 25%.

---

## 3. Monarchy (Chế độ quân chủ)
- **Biểu tượng:** `♔` | **Màu sắc:** Tím
- **Buff:**
  - **Tự trị:** Chi phí tạo và mở rộng vùng đất bảo vệ (claim đất qua ShopGuard) được giảm một nửa (-50%).
  - **Phép vua thua lệ làng:** Tăng 15% sát thương gây ra và tăng 15% khả năng chống chịu (giảm 15% sát thương nhận vào) khi bạn đang đứng trong chính vùng đất đã claim của mình.
- **Debuff:**
  - **Cống nạp:** Đóng thêm khoản thuế cống nạp bằng với lượng thuế hàng ngày (được trừ trực tiếp và tiêu hủy).
  - **Nhập khẩu:** Có tỉ lệ 50% khi mua sắm vật phẩm (shop, chợ `/ah`, đơn đặt hàng) phải chịu thêm 50% thuế nhập khẩu tính trên tiền thuế của món đó.

---

## 4. Anarchism (Chủ nghĩa vô chính phủ)
- **Biểu tượng:** `Ⓐ` | **Màu sắc:** Trắng / Xám (Mặc định khi chưa chọn phe)
- **Buff:**
  - **Tự do:** Hoàn toàn không phải đóng bất kỳ loại thuế nào (thuế giao dịch, thuế hàng ngày, thuế toll... đều về 0; vẫn trả phí mua đồ và phí toll gốc).
  - **Thoải mái:** Tăng 15% tốc độ chạy bộ và 15% tốc độ di chuyển trên ngựa khi đang đứng trên vùng đất hoang dã chưa bị ai claim.
- **Debuff:**
  - **Vô chính phủ:** Không được phép sở hữu hoặc claim vùng đất mới; không thể nhận chuyển nhượng đất từ người chơi khác; không thể được thêm vào danh sách tin tưởng (`/claim trust`) trên vùng đất của người khác.
