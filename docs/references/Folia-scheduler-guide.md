---
title: 'Bukkit to Folia Scheduler Migration Guideline for AI Agents'
version: '1.0'
target_framework: 'Folia / PaperMC'
description: 'Quy tắc và mẫu code chuẩn để AI Agent tự động refactor BukkitRunnable sang Folia Schedulers.'
---

# LỜI MỞ ĐẦU DÀNH CHO AGENT

**Ngữ cảnh hệ thống:** Folia loại bỏ hoàn toàn khái niệm "Main Thread" (Luồng chính) của Bukkit/Spigot truyền thống và thay bằng hệ thống đa luồng chia theo vùng (Regionized Multithreading).
**Nhiệm vụ của Agent:** Khi phát hiện code sử dụng `BukkitRunnable` hoặc `Bukkit.getScheduler()`, Agent PHẢI refactor chúng sang hệ thống Scheduler mới của Folia dựa trên cây quyết định dưới đây.

---

## 1. QUY TẮC NGHIÊM NGẶT (STRICT RULES)

1. **CẤM** sử dụng `Bukkit.getScheduler()`.
2. **CẤM** sử dụng `new BukkitRunnable()`.
3. Mọi tác vụ phải được gắn với một **ngữ cảnh cụ thể** (Entity, Location, Global, hoặc Async).
4. Các tác vụ Async (`AsyncScheduler`) sử dụng `TimeUnit` (Giây, Phút, Milliseconds) thay vì `Ticks`.
5. Các tác vụ Sync (Entity, Region, Global) vẫn sử dụng đơn vị `Ticks` (1 tick = 50ms).
6. Để hủy một task (thay thế cho `this.cancel()`), phải gọi `task.cancel()` từ đối tượng `ScheduledTask` được truyền vào Lambda/Consumer.
7. **CẤM** sử dụng `HashMap`, `ArrayList` hoặc các dạng collection không thread-safe trong các class quản lý dữ liệu (Data Manager) để chứa shared data. Yêu cầu đổi thành `ConcurrentHashMap`, `CopyOnWriteArrayList` hoặc các thread-safe collection tương ứng.

---

## 2. CÂY RA QUYẾT ĐỊNH (DECISION TREE)

Khi refactor một task, Agent hãy phân tích các biến được truy cập bên trong task đó:

- **IF** task tương tác, thay đổi dữ liệu của một `Entity` hoặc `Player`:
  - ➡️ **THEN** Sử dụng `entity.getScheduler()`.
- **ELSE IF** task thay đổi một `Block`, `Chunk`, hoặc thao tác tại một `Location`:
  - ➡️ **THEN** Sử dụng `Bukkit.getRegionScheduler()`.
- **ELSE IF** task thực hiện I/O (Database, File, HTTP Request) hoặc tính toán độc lập:
  - ➡️ **THEN** Sử dụng `Bukkit.getAsyncScheduler()`.
- **ELSE** (Task tương tác với GameRules, Time, Weather, WorldBorder, Broadcast):
  - ➡️ **THEN** Sử dụng `Bukkit.getGlobalRegionScheduler()`.

---

## 3. MẪU CODE CHUYỂN ĐỔI (CODE PATTERNS)

### Pattern 1: Async Tasks (Database, I/O)

_Đơn vị thời gian: `TimeUnit`_

**[OLD - Bukkit]**

```java
new BukkitRunnable() {
    @Override
    public void run() {
        // I/O Task
    }
}.runTaskAsynchronously(plugin);
```
