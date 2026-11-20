package com.ghosthunt.building;

import java.util.List;

public enum RoomType {
    STAIRWELL("樓梯間", "梯"),
    JAIL("監獄", "監"),
    GHOST_ROOM("鬼房", "鬼"),
    OPEN_OFFICE("開放式辦公室", "辦"),
    CUBICLES("隔間辦公室", "隔"),
    BOARDROOM("大型會議室", "會"),
    ARCHIVE("檔案室", "檔"),
    STORAGE("儲藏室", "儲"),
    LOUNGE("員工休息室", "休"),
    ELECTRICAL("電力室", "電"),
    SERVER("伺服器機房", "機"),
    COPY_ROOM("影印室", "印"),
    TOILET("公共廁所", "廁");

    /** 一般房間（隨機分配的那些）。 */
    public static final List<RoomType> NORMAL = List.of(
            OPEN_OFFICE, CUBICLES, BOARDROOM, ARCHIVE, STORAGE, LOUNGE, ELECTRICAL, SERVER, COPY_ROOM, TOILET);

    public final String label;
    /** 一個字的簡稱（/ghosthunt info 的俯視圖用）。 */
    public final String symbol;

    RoomType(String label, String symbol) {
        this.label = label;
        this.symbol = symbol;
    }

    /** 只有一個出入口的房間。 */
    public boolean deadEnd() {
        return this == TOILET;
    }

    public boolean normal() {
        return NORMAL.contains(this);
    }
}
