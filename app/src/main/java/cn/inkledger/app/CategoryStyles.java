package cn.inkledger.app;

import android.content.Context;

final class CategoryStyles {
    static final int[] COLORS = {0xff525bd4, 0xff278478, 0xffba6376, 0xffab6e36, 0xff8154b2, 0xff4578a5};
    static final String[] COLOR_NAMES = {"靛蓝", "青绿", "玫瑰", "琥珀", "紫藤", "湖蓝"};
    static final String[] SYMBOLS = {"✦", "◆", "●", "✿", "☾", "✚"};

    private CategoryStyles() {}

    static Expense.Category resolve(Context context, Expense.Category category) {
        if (!category.name().startsWith("custom:")) return category;
        int style = context.getSharedPreferences("categories", Context.MODE_PRIVATE)
                .getInt("style:" + category.label, -1);
        if (style < 0 || style >= COLORS.length * SYMBOLS.length) return category;
        return category.styled(COLORS[style / SYMBOLS.length], SYMBOLS[style % SYMBOLS.length]);
    }
}
