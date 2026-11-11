package cn.inkledger.app;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

final class Expense {
    private final long id;
    private final long cents;
    private final String merchant;
    private final Category category;
    private final String source;
    private final long paidAt;

    Expense(long id, long cents, String merchant, Category category, String source, long paidAt) {
        this.id = id;
        this.cents = cents;
        this.merchant = merchant;
        this.category = category;
        this.source = source;
        this.paidAt = paidAt;
    }

    long id() { return id; }
    long cents() { return cents; }
    String merchant() { return merchant; }
    Category category() { return category; }
    String source() { return source; }
    long paidAt() { return paidAt; }
    LocalDate date() {
        return Instant.ofEpochMilli(paidAt).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    static String money(long cents) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.CHINA);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "¥" + format.format(java.math.BigDecimal.valueOf(cents, 2));
    }

    static final class Category {
        static final Category FOOD = new Category("FOOD", "餐饮", 0xff547d66, "食");
        static final Category TRANSPORT = new Category("TRANSPORT", "交通", 0xff6889a6, "行");
        static final Category SHOPPING = new Category("SHOPPING", "购物", 0xffbf885a, "购");
        static final Category DAILY = new Category("DAILY", "日用", 0xff879766, "用");
        static final Category ENTERTAINMENT = new Category("ENTERTAINMENT", "娱乐", 0xffaa7796, "乐");
        static final Category HEALTH = new Category("HEALTH", "医疗", 0xffb96e66, "医");
        static final Category OTHER = new Category("OTHER", "其他", 0xff778383, "他");
        static final Category UNKNOWN = new Category("UNKNOWN", "待分类", 0xff9e9786, "待");

        private static final Category[] BUILT_INS = {
                FOOD, TRANSPORT, SHOPPING, DAILY, ENTERTAINMENT, HEALTH, OTHER, UNKNOWN
        };
        private static final int[] COLORS = {0xff6467bd, 0xff538b86, 0xffa86584, 0xffa87c49};
        private static final String CUSTOM = "custom:";
        private final String key;
        final String label;
        final int color;
        final String mark;

        private Category(String key, String label, int color, String mark) {
            this.key = key;
            this.label = label;
            this.color = color;
            this.mark = mark;
        }

        String name() { return key; }

        Category styled(int color, String mark) {
            return new Category(key, label, color, mark);
        }

        static Category[] values() { return BUILT_INS.clone(); }

        static Category valueOf(String key) {
            for (Category category : BUILT_INS) if (category.key.equals(key)) return category;
            if (key != null && key.startsWith(CUSTOM) && key.length() > CUSTOM.length()) {
                return custom(key.substring(CUSTOM.length()));
            }
            return UNKNOWN;
        }

        static Category custom(String name) {
            int color = COLORS[Math.floorMod(name.hashCode(), COLORS.length)];
            return new Category(CUSTOM + name, name, color,
                    name.substring(0, name.offsetByCodePoints(0, 1)));
        }

        @Override public boolean equals(Object other) {
            return other instanceof Category category && key.equals(category.key);
        }

        @Override public int hashCode() { return key.hashCode(); }

        static Category guess(String merchant, String item) {
            String text = (merchant + " " + item).toLowerCase(Locale.ROOT);
            if (contains(text, "咖啡", "奶茶", "瑞幸", "星巴克", "餐饮", "餐厅", "饭店", "外卖",
                    "美食", "烧烤", "汉堡", "小吃", "火锅", "麦当劳", "肯德基", "面馆", "早餐", "茶饮")) return FOOD;
            if (contains(text, "地铁", "公交", "滴滴", "打车", "高德打车", "出行", "停车", "加油", "铁路", "交通", "出租车")) return TRANSPORT;
            if (contains(text, "药房", "药店", "医院", "门诊", "挂号", "体检", "诊所")) return HEALTH;
            if (contains(text, "影院", "电影", "演出", "剧场", "游戏", "乐园", "博物馆", "ktv")) return ENTERTAINMENT;
            if (contains(text, "超市", "便利店", "菜场", "菜市场", "生鲜", "水费", "电费", "燃气费", "话费")) return DAILY;
            if (contains(text, "淘宝", "天猫", "京东", "拼多多", "唯品会", "服装", "旗舰店", "优衣库", "专卖店")) return SHOPPING;
            return UNKNOWN;
        }

        private static boolean contains(String text, String... words) {
            for (String word : words) if (text.contains(word)) return true;
            return false;
        }
    }
}
