# AntiESPUltimate

Gộp 3 module phòng thủ vào một plugin:

1. **Block obfuscation** — thay ore + mọi loại container (chest, ender chest,
   barrel, furnace, shulker box, dispenser, dropper, hopper...) bằng block giả
   trong gói chunk gửi đi. Chỉ hiện block thật cho người chơi nào thật sự lại
   gần hoặc có line-of-sight hợp lệ. Đây là phần trực tiếp vô hiệu hoá
   **StorageESP/OreESP/cave-finder** vì client hack chỉ nhận được dữ liệu giả.
2. **Player visibility** — ẩn người chơi khỏi nhau khi bị block chắn tầm nhìn
   (raycast), dùng `Player#hidePlayer`/`showPlayer` chuẩn của Bukkit thay vì tự
   dựng reflection NMS, nên đỡ gãy khi lên version mới.
3. **View distance limiter** — giảm view-distance gửi cho từng người chơi
   (Paper API), giới hạn phạm vi dữ liệu client từng nhận được.

## Về FreeCam — đọc kỹ trước khi kỳ vọng

**Không có plugin server nào chặn được FreeCam**, kể cả plugin này. FreeCam chỉ
tách camera hiển thị ra khỏi nhân vật ở phía client, không gửi thêm gói tin
gì bất thường lên server — nhân vật vẫn "đứng yên" theo dữ liệu server nhận
được. Server không có cơ sở nào để phân biệt "đang dùng freecam" với "đang
đứng yên nhìn quanh" bình thường.

Việc `view-distance-limit` làm được chỉ là giảm **phạm vi dữ liệu** mà server
từng gửi cho client — tức là freecam có bay xa cỡ nào cũng không "nhìn" được
xa hơn những gì server đã gửi. Kết hợp với block-obfuscation, ngay cả khi
FreeCam bay xuyên tường nhìn vào một khu vực đã tải, nó cũng chỉ thấy đá giả
thay vì rương/quặng thật cho tới khi có ai đó thật sự tiếp cận hợp lệ.

Nếu cần một lớp phòng thủ nữa, cân nhắc bổ sung (không có trong code này):
- Cảnh báo admin khi người chơi đứng yên bất thường lâu trong khi hướng nhìn
  liên tục đổi về phía các vị trí có giá trị (heuristic, không chắc chắn).
- Giới hạn `view-distance`/`simulation-distance` toàn server trong
  `server.properties` như một lớp phòng thủ song song.

## Build

### Cách 1 — GitHub Actions (không cần cài gì trên máy)

Project đã có sẵn `.github/workflows/build.yml`. Các bước:

1. Tạo một repo GitHub mới (public hoặc private đều được).
2. Đẩy toàn bộ nội dung thư mục `AntiESPUltimate/` (giữ nguyên cấu trúc,
   kể cả thư mục ẩn `.github/`) lên repo đó:
   ```bash
   cd AntiESPUltimate
   git init
   git add .
   git commit -m "initial commit"
   git branch -M main
   git remote add origin <URL repo GitHub của bạn>
   git push -u origin main
   ```
3. Vào tab **Actions** trên GitHub repo → sẽ thấy workflow "Build plugin jar"
   tự chạy sau khi push (mất khoảng 1-2 phút).
4. Khi chạy xong (dấu tích xanh), bấm vào lần chạy đó → kéo xuống mục
   **Artifacts** → tải file `AntiESPUltimate-jar.zip` về. Trong đó chính là
   `AntiESPUltimate.jar` bạn cần.
5. Nếu không muốn đợi push code, vào tab Actions → chọn workflow → bấm
   **Run workflow** để chạy thủ công bất cứ lúc nào.

### Cách 2 — Build trên máy có Maven + JDK 17

```bash
mvn clean package
```

File `target/AntiESPUltimate.jar` sinh ra, bỏ vào thư mục `plugins/` cùng
`ProtocolLib.jar` (bắt buộc phải cài, xem `plugin.yml`).

## Trước khi đưa lên server thật

- Test trên server dev, không phải production, ít nhất vài ngày.
- `block-obfuscation` viết lại toàn bộ block data trong MỌI gói chunk gửi đi —
  cân nhắc tác động hiệu năng trên server đông người, đặc biệt vòng lặp
  `revealTick` (O(số người chơi × bán kính³)); nếu lag, tăng
  `reveal-check-interval` hoặc giảm `reveal-distance`.
- Đây là bản khởi điểm minh hoạ kỹ thuật, chưa xử lý các edge case như: người
  chơi đặt/phá block đúng vị trí đang bị obfuscate, đồng bộ khi nhiều người
  chơi cùng lúc reveal một block, hay chunk resend khi thế giới thay đổi.
  Test kỹ trước khi tin tưởng hoàn toàn.
