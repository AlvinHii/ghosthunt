package com.ghosthunt.game.ai;

/**
 * 看時機用能力（純函式）：關電源要「關掉以後他們要花很久才修得回來」才值得。
 */
public final class AbilityTiming {

    private AbilityTiming() {}

    /** 不知道人在哪時，當成他們離電源這麼多秒。 */
    public static final double UNKNOWN_SECONDS = 30;

    /**
     * 關掉的價值 = 他們走回電源的平均秒數（鬼最後知道的位置估的；不知道就 30 秒）− 鬼走過去的秒數；≥ minAdvantage 才去關。
     */
    public static boolean shouldCutPower(double[] survivorSecondsToLever, double ghostSecondsToLever, double minAdvantage) {
        double avg = UNKNOWN_SECONDS;
        if (survivorSecondsToLever.length > 0) {
            double s = 0;
            for (double v : survivorSecondsToLever) s += Double.isInfinite(v) ? UNKNOWN_SECONDS : v;
            avg = s / survivorSecondsToLever.length;
        }
        return avg - ghostSecondsToLever >= minAdvantage;
    }
}
