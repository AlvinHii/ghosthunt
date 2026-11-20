package com.ghosthunt.building;

/** 方塊座標（世界絕對座標）。 */
public record Pos(int x, int y, int z) {

    public Pos add(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public double distance(Pos o) {
        int dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
