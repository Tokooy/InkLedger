package cn.inkledger.app;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PaymentParser {
    private static final Pattern SUCCESS = Pattern.compile(
            "^(?:交易状态:?|当前状态:?)?(?:支付成功|付款成功|交易成功|支付完成|付款完成|已支付|已付款)$");
    private static final Pattern SUCCESS_HEADING = Pattern.compile("^(?:支付|付款)(?:成功|完成)$");
    private static final Pattern REJECT = Pattern.compile(
            "^(?:(?:当前状态|交易状态|交易类型):?)?(?:待付款|等待付款|支付失败|交易失败|交易关闭|已关闭|退款成功|已退款|部分退款|退款中|退款详情|转账详情|转账成功|微信转账|个人转账|收款成功|红包|红包详情|充值成功|待支付|支付中|付款中|支付取消|订单取消|交易取消|已取消|收款详情|支付确认|确认支付|确认付款)(?:[:(].*)?$");
    private static final Pattern FIELD = Pattern.compile(
            "^(?:商户全称|商家名称|商户名称|商家全称|收款方全称|商户名|商家|商户|收款方|收款商户|收款机构|商户简称|商品说明|商品详情|商品名称|商品|订单标题|交易单号|交易号|微信支付单号|支付宝交易号|商户单号|商户订单号|订单号|付款时间|支付时间|交易时间|创建时间|付款方式|支付方式|收款方式|交易状态|当前状态|实付金额|实付款|实付|支付金额|付款金额|金额|优惠|退款|账单详情|交易详情|支付详情|完成|返回商家|返回商户|返回|查看账单详情|查看交易详情|查看支付详情|查看账单|查看详情|账单服务|支付凭证|支付成功|付款成功)(?:[: ].*|$)");
    private static final String AMOUNT_TOKEN = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{2})?";
    private static final Pattern NUMBER = Pattern.compile("^[-−]?(?:[¥￥])?(" + AMOUNT_TOKEN + ")(?:元)?$");
    private static final Pattern EXPLICIT = Pattern.compile(
            "^(实付金额|实付款|实付|支付金额|付款金额)[: ]*(?:[-−]?[¥￥]?)?(" + AMOUNT_TOKEN + ")(?:元)?$");
    private static final Pattern DATE = Pattern.compile(
            "(20[0-9]{2})[-/.年]([0-9]{1,2})[-/.月]([0-9]{1,2})日?\\s+([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?");
    private static final Pattern ORDER = Pattern.compile("^[A-Za-z0-9-]{12,64}$");
    private static final String MERCHANT = "收款方全称|商户全称|商家全称|商户名称|商家名称|收款商户|收款机构|商户简称|商户名|收款方|商户|商家";
    private static final String PRODUCT = "商品说明|商品名称|商品详情|订单标题|商品";
    private static final Pattern PAYEE = Pattern.compile("^向\\s*(.{2,60}?)\\s*付款$");
    private static final Pattern RECEIPT_ACTION = Pattern.compile("^(?:完成|返回商家|返回商户|查看账单|查看账单详情|查看支付详情|查看交易详情)$");

    record Result(long cents, String merchant, Expense.Category category, String source,
                  long paidAt, String order) {}

    private PaymentParser() {}

    static Result parse(List<String> rawRows, long capturedAt) {
        List<String> rows = new ArrayList<>();
        for (String raw : rawRows) {
            String row = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
            if (!row.isEmpty()) rows.add(row);
        }
        if (rows.isEmpty() || rows.stream().map(PaymentParser::compact)
                .anyMatch(row -> REJECT.matcher(row).matches())) return null;

        boolean success = false;
        int heading = -1;
        for (int i = 0; i < rows.size(); i++) {
            String line = compact(rows.get(i));
            if (SUCCESS.matcher(line).matches()) success = true;
            if (i < 4 && SUCCESS_HEADING.matcher(line).matches()) heading = i;
        }
        String merchant = field(rows, MERCHANT);
        String item = field(rows, PRODUCT);
        String order = field(rows, "微信支付单号|支付宝交易号|交易单号|交易号");
        String time = field(rows, "支付时间|付款时间|交易时间");
        String method = field(rows, "付款方式|支付方式");
        boolean detail = success && hasText(merchant) && (hasText(time) || hasText(order) || hasText(method));
        boolean receipt = heading >= 0 && rows.size() <= 26 && (hasText(merchant)
                || rows.stream().map(PaymentParser::compact).anyMatch(row ->
                RECEIPT_ACTION.matcher(row).matches() || PAYEE.matcher(row).matches()));
        if (!detail && !receipt) return null;

        long amount = explicitAmount(rows);
        if (amount == -2) return null;
        if (amount < 0 && receipt) amount = receiptAmount(rows, heading);
        if (amount < 0 && detail) amount = detailHeaderAmount(rows, merchant);
        if (amount <= 0) return null;

        if (!hasText(merchant) && receipt) {
            for (int i = heading + 1; i < Math.min(rows.size(), heading + 5); i++) {
                String candidate = rows.get(i);
                Matcher payee = PAYEE.matcher(candidate);
                if (payee.matches()) candidate = payee.group(1);
                if (plausibleMerchant(candidate)) {
                    merchant = candidate;
                    break;
                }
            }
        }
        if (!plausibleMerchant(merchant)) return null;
        String all = String.join("\n", rows);
        boolean wechat = all.contains("微信支付") || all.contains("微信零钱");
        boolean alipay = all.contains("支付宝") || all.contains("花呗");
        String source = wechat == alipay ? "截图识别" : wechat ? "微信支付" : "支付宝";
        String orderId = compact(order);
        return new Result(amount, merchant, Expense.Category.guess(merchant, item), source,
                paymentTime(time, capturedAt), ORDER.matcher(orderId).matches() ? orderId : "");
    }

    private static boolean plausibleMerchant(String value) {
        if (!hasText(value) || value.length() < 2 || value.length() > 60) return false;
        String clean = compact(value);
        return !FIELD.matcher(value).matches() && !SUCCESS.matcher(clean).matches()
                && !REJECT.matcher(clean).matches() && !NUMBER.matcher(clean).matches()
                && !clean.matches("^(?:支付宝|微信支付|微信|账单|支付结果|完成|查看详情|返回|交易成功|商户服务|支付有优惠)$")
                && !clean.matches(".*(?:退款|红包|转账|优惠|奖励|积分|抽奖|服务通知|扫码|聊天记录|商品推荐|付款|支付).*")
                && value.codePoints().anyMatch(Character::isLetter);
    }

    private static long explicitAmount(List<String> rows) {
        long paid = -1;
        long payment = -1;
        for (int i = 0; i < rows.size(); i++) {
            String row = compact(rows.get(i));
            if (row.matches("^(?:实付金额|实付款|实付|支付金额|付款金额):?$") && i + 1 < rows.size()) {
                row += compact(rows.get(i + 1));
            }
            Matcher match = EXPLICIT.matcher(row);
            if (!match.matches()) continue;
            long value = toCents(match.group(2));
            if (value < 0) continue;
            if (match.group(1).startsWith("实付")) paid = merge(paid, value);
            else payment = merge(payment, value);
        }
        return paid == -1 ? payment : paid;
    }

    private static long merge(long previous, long next) {
        return previous == -1 || previous == next ? next : -2;
    }

    private static long receiptAmount(List<String> rows, int heading) {
        return mainAmount(rows, Math.max(0, heading - 2), Math.min(rows.size(), heading + 5));
    }

    private static long detailHeaderAmount(List<String> rows, String merchant) {
        int merchantRow = rows.indexOf(merchant);
        int end = merchantRow > 0 ? Math.min(merchantRow + 3, 7) : 5;
        return mainAmount(rows, 0, Math.min(rows.size(), end));
    }

    private static long mainAmount(List<String> rows, int from, int to) {
        long candidate = -1;
        for (int i = from; i < to; i++) {
            String row = compact(rows.get(i));
            if (!row.contains("¥") && !row.contains("￥") && !row.contains(".") && !row.endsWith("元")) continue;
            if (i > 0 && compact(rows.get(i - 1)).matches(".*(?:优惠|原价|应付|退款|立减|折扣|订单金额|商品金额)[:：]?$")) continue;
            Matcher matcher = NUMBER.matcher(row);
            if (matcher.matches()) {
                long value = toCents(matcher.group(1));
                if (value >= 0) candidate = merge(candidate, value);
            }
        }
        return candidate;
    }

    private static long toCents(String text) {
        try {
            long cents = new BigDecimal(text.replace(",", "")).movePointRight(2).longValueExact();
            return cents >= 0 && cents <= 100_000_000_00L ? cents : -1;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1;
        }
    }

    private static String field(List<String> rows, String keys) {
        Pattern prefix = Pattern.compile("^(" + keys + ")(?:[: ]+(.*))?$");
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            Matcher match = prefix.matcher(row);
            if (!match.matches()) continue;
            if (hasText(match.group(2))) return match.group(2).strip();
            if (i + 1 < rows.size() && !FIELD.matcher(rows.get(i + 1)).matches()) return rows.get(i + 1);
        }
        return "";
    }

    private static long paymentTime(String time, long capturedAt) {
        Matcher matcher = DATE.matcher(time);
        if (matcher.find()) {
            try {
                long paid = LocalDateTime.of(
                        Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)),
                        Integer.parseInt(matcher.group(5)), matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6)))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (paid <= capturedAt + 120_000) return paid;
            } catch (DateTimeException ignored) { }
        }
        return capturedAt;
    }

    private static String compact(String text) {
        return text.replace(" ", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
