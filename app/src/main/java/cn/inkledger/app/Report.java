package cn.inkledger.app;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Report {
    final long[] days;
    final Map<Expense.Category, Long> categories = new LinkedHashMap<>();
    final Map<LocalDate, List<Expense>> byDate = new LinkedHashMap<>();
    final List<Expense> expenses;
    final long total;
    final int spendingDays;

    Report(YearMonth month, List<Expense> expenses) {
        this.expenses = expenses;
        days = new long[month.lengthOfMonth()];
        Map<Expense.Category, Long> sums = new LinkedHashMap<>();
        long amount = 0;
        for (Expense expense : expenses) {
            LocalDate date = expense.date();
            if (!YearMonth.from(date).equals(month)) continue;
            amount += expense.cents();
            days[date.getDayOfMonth() - 1] += expense.cents();
            sums.merge(expense.category(), expense.cents(), Long::sum);
            byDate.computeIfAbsent(date, key -> new ArrayList<>()).add(expense);
        }
        total = amount;
        spendingDays = byDate.size();
        sums.entrySet().stream().sorted(Map.Entry.<Expense.Category, Long>comparingByValue().reversed())
                .forEach(entry -> categories.put(entry.getKey(), entry.getValue()));
    }
}
