package cn.inkledger.app;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BillListParser {
    private static final Pattern ENTRY = Pattern.compile("^(.+?)\\s*[-−]\\s*¥?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*\\.\\s*(\\d{2})$");
    private static final Pattern DATE = Pattern.compile("^(?:(20\\d{2})[-/])?(\\d{1,2})[-/](\\d{1,2})\\s+(\\d{1,2})\\s*:\\s*(\\d{2})$");
    private static final Pattern REJECT = Pattern.compile("退款|待付款|付款失败|交易关闭|退款成功|已关闭|转账|红包");
    private static final Pattern CATEGORY = Pattern.compile("^(餐饮美食|餐饮|文化休闲|休闲娱乐|日用百货|交通出行|服饰美容|数码电器|购物|医疗健康|生活缴费|其他)(?:自动扣款成功|支付成功)?$");

    private BillListParser() {}

    static List<PaymentParser.Result> parse(List<String> input, long capturedAt) {
        List<String> rows = input.stream().map(s -> Normalizer.normalize(s, Normalizer.Form.NFKC).strip())
                .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toList());
        List<PaymentParser.Result> results = new ArrayList<>();
        for (int i = 0; i + 2 < rows.size(); i++) {
            Matcher amount = ENTRY.matcher(rows.get(i));
            int categoryIndex = i + 1;
            if (!amount.matches() && rows.get(i + 1).matches("^[-−].*")) {
                amount = ENTRY.matcher(rows.get(i) + " " + rows.get(i + 1));
                categoryIndex++;
            }
            if (!amount.matches() || categoryIndex + 1 >= rows.size()) continue;
            Matcher category = CATEGORY.matcher(rows.get(categoryIndex).replaceAll("[\\s|丨]+", ""));
            Matcher date = DATE.matcher(rows.get(categoryIndex + 1));
            if (!category.matches() || !date.matches()) continue;

            String merchant = amount.group(1).strip();
            if (merchant.length() > 80 || REJECT.matcher(merchant).find()
                    || merchant.codePoints().noneMatch(Character::isLetter)) continue;
            long paid = dateMillis(date, capturedAt);
            if (paid < 0) continue;
            try {
                long cents = new BigDecimal(amount.group(2).replace(",", "") + "." + amount.group(3))
                        .movePointRight(2).longValueExact();
                if (cents <= 0 || cents > 100_000_000_00L) continue;
                results.add(new PaymentParser.Result(cents, merchant, category(category.group(1)),
                        "账单截图", paid, ""));
                i = categoryIndex + 1;
            } catch (ArithmeticException ignored) {
                // Ignore values outside the supported range.
            }
        }
        return results.size() >= 2 ? results : List.of();
    }

    private static Expense.Category category(String label) {
        return switch (label) {
            case "餐饮美食", "餐饮" -> Expense.Category.FOOD;
            case "文化休闲", "休闲娱乐" -> Expense.Category.ENTERTAINMENT;
            case "日用百货", "生活缴费" -> Expense.Category.DAILY;
            case "交通出行" -> Expense.Category.TRANSPORT;
            case "服饰美容", "数码电器", "购物" -> Expense.Category.SHOPPING;
            case "医疗健康" -> Expense.Category.HEALTH;
            default -> Expense.Category.OTHER;
        };
    }

    private static long dateMillis(Matcher date, long capturedAt) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime captured = Instant.ofEpochMilli(capturedAt).atZone(zone).toLocalDateTime();
        boolean hasYear = date.group(1) != null;
        try {
            int year = hasYear ? Integer.parseInt(date.group(1)) : captured.getYear();
            LocalDateTime time = LocalDateTime.of(year, Integer.parseInt(date.group(2)),
                    Integer.parseInt(date.group(3)), Integer.parseInt(date.group(4)), Integer.parseInt(date.group(5)));
            if (!hasYear && time.isAfter(captured.plusMinutes(2))) time = time.minusYears(1);
            if (time.isAfter(captured.plusMinutes(2))) return -1;
            return time.atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeException invalid) {
            return -1;
        }
    }
}
