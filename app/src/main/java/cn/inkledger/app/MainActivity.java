package cn.inkledger.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = 0xfff1f3f9;
    private static final int WHITE = Color.WHITE;
    private static final int BLUE = Charts.BLUE;
    private static final int INK = Charts.INK;
    private static final int MUTED = Charts.SECONDARY;
    private static final int TINT = 0xffeaf0ff;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int SCREENSHOT_FOLDER = 101;
    private static final int NOTIFICATIONS = 102;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M 月 d 日 · EEEE", Locale.CHINA);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);

    private enum Page {
        CATEGORY("分类"), DAYS("每日"), DETAILS("明细");
        final String label;
        Page(String label) { this.label = label; }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private YearMonth month = YearMonth.now();
    private Page page = Page.CATEGORY;
    private int day = LocalDate.now().getDayOfMonth();
    private Expense.Category filter;
    private List<Expense> expenses = List.of();
    private boolean loading = true;
    private boolean starting;
    private int requestNumber;

    private FrameLayout frame;
    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout nav;
    private ScrollView scroll;
    private LedgerPager pager;
    private final java.util.EnumMap<Page, Integer> positions = new java.util.EnumMap<>(Page.class);
    private boolean refreshPending;
    private Expense.Category pendingFilter;
    private boolean fullScreenOverlay;
    private boolean closingOverlay;
    private FrameLayout overlay;
    private final OnBackAnimationCallback dismiss = new OnBackAnimationCallback() {
        @Override public void onBackStarted(BackEvent event) {
            if (fullScreenOverlay && !closingOverlay && overlay != null) {
                overlay.animate().cancel();
                root.animate().cancel();
            }
        }

        @Override public void onBackProgressed(BackEvent event) {
            if (!fullScreenOverlay || closingOverlay || overlay == null) return;
            float width = frame.getWidth();
            overlay.setTranslationX(width * event.getProgress());
            root.setTranslationX(-width * .16f * (1 - event.getProgress()));
        }

        @Override public void onBackCancelled() {
            if (!fullScreenOverlay || closingOverlay || overlay == null) return;
            overlay.animate().translationX(0).setDuration(200).setInterpolator(slideCurve()).start();
            root.animate().translationX(-frame.getWidth() * .16f).setDuration(200)
                    .setInterpolator(slideCurve()).start();
        }

        @Override public void onBackInvoked() { closeSheet(); }
    };
    private final OnBackInvokedCallback returnToCategories = () -> switchPage(Page.CATEGORY);
    private boolean drillBackRegistered;

    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            starting = false;
            load(false);
        }
    };

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            month = YearMonth.parse(saved.getString("month"));
            String previousPage = saved.getString("page");
            if (previousPage != null && !previousPage.equals("SETTINGS")) page = Page.valueOf(previousPage);
            day = saved.getInt("day");
            String category = saved.getString("filter");
            if (category != null) filter = Expense.Category.valueOf(category);
        }

        frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        root = column();
        frame.addView(root, new FrameLayout.LayoutParams(MATCH, MATCH));
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            frame.setPadding(bars.left, bars.top, bars.right, bottom);
            return insets;
        });


        pager = new LedgerPager(this, Page.values().length, new LedgerPager.Pages() {
            @Override public View create(int position) {
                positions.put(page, scroll.getScrollY());
                return buildPage(Page.values()[position], pendingFilter);
            }

            @Override public void settled(int position, View view) {
                Page next = Page.values()[position];
                if (next != page) {
                    page = next;
                    filter = pendingFilter;
                }
                pendingFilter = null;
                scroll = (ScrollView) view;
                content = (LinearLayout) scroll.getChildAt(0);
                renderNav();
                if (refreshPending) {
                    refreshPending = false;
                    render(false);
                }
            }
        });
        scroll = buildPage(page, filter);
        content = (LinearLayout) scroll.getChildAt(0);
        pager.initialize(page.ordinal(), scroll);
        root.addView(pager, new LinearLayout.LayoutParams(MATCH, 0, 1));

        nav = row();
        nav.setPadding(dp(5), dp(5), dp(5), dp(5));
        nav.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xfaffffff, 0xe8f9faff}));
        GradientDrawable navBackground = (GradientDrawable) nav.getBackground();
        navBackground.setCornerRadius(dp(27));
        navBackground.setStroke(dp(1), 0xffe7ebf6);
        nav.setElevation(dp(3));
        LinearLayout.LayoutParams navSize = new LinearLayout.LayoutParams(MATCH, WRAP);
        navSize.setMargins(dp(20), 0, dp(20), dp(8));
        root.addView(nav, navSize);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().setStatusBarContrastEnforced(false);
        setContentView(frame);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(light, light);
        }
        registerReceiver(changed, new IntentFilter(ScreenshotService.CHANGED), RECEIVER_NOT_EXPORTED);
        render(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!starting && ScreenshotFolder.resumeEnabled(this) && ScreenshotFolder.selected(this) != null) {
            if (ScreenshotService.isActive()) startService(new Intent(this, ScreenshotService.class));
            else startMonitor();
        }
        load(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle saved) {
        saved.putString("month", month.toString());
        saved.putString("page", page.name());
        saved.putInt("day", day);
        if (filter != null) saved.putString("filter", filter.name());
        super.onSaveInstanceState(saved);
    }

    @Override
    protected void onDestroy() {
        removeOverlay();
        if (drillBackRegistered) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(returnToCategories);
        unregisterReceiver(changed);
        io.shutdown();
        super.onDestroy();
    }

    private void load(boolean resetScroll) {
        int request = ++requestNumber;
        YearMonth requested = month;
        io.execute(() -> {
            try {
                List<Expense> rows = Ledger.get(this).month(requested);
                runOnUiThread(() -> {
                    if (request != requestNumber || isDestroyed()) return;
                    expenses = rows;
                    loading = false;
                    render(resetScroll);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (request == requestNumber && !isDestroyed()) {
                        loading = false;
                        render(resetScroll);
                        Toast.makeText(this, "读取本地账本失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private ScrollView buildPage(Page target, Expense.Category targetFilter) {
        ScrollView view = new ScrollView(this);
        view.setFillViewport(true);
        view.setVerticalScrollBarEnabled(false);
        LinearLayout pageContent = column();
        pageContent.setPadding(dp(20), dp(14), dp(20), dp(18));
        view.addView(pageContent, new ScrollView.LayoutParams(MATCH, WRAP));
        Page originalPage = page;
        Expense.Category originalFilter = filter;
        LinearLayout originalContent = content;
        page = target;
        filter = targetFilter;
        content = pageContent;
        try {
            renderPage();
        } finally {
            page = originalPage;
            filter = originalFilter;
            content = originalContent;
        }
        int position = targetFilter == null ? positions.getOrDefault(target, 0) : 0;
        view.post(() -> view.scrollTo(0, position));
        return view;
    }

    private void render(boolean resetScroll) {
        if (pager.moving()) {
            refreshPending = true;
            return;
        }
        int scrollY = resetScroll ? 0 : scroll.getScrollY();
        content.removeAllViews();
        renderPage();
        renderNav();
        ScrollView current = scroll;
        current.post(() -> current.scrollTo(0, scrollY));
    }

    private void renderPage() {
        renderHeader();
        renderMonthSelector();
        if (loading) {
            LinearLayout wait = card();
            wait.addView(label("正在读取账本", 15, MUTED, false));
            addCard(wait);
            return;
        }
        Report report = new Report(month, expenses);
        switch (page) {
            case CATEGORY -> { renderHero(report); renderCategories(report); }
            case DAYS -> renderDays(report);
            case DETAILS -> renderDetails(report);
        }
    }

    private void renderHeader() {
        LinearLayout header = row();
        LinearLayout title = column();
        title.addView(label("墨账", 32, INK, true));
        TextView subtitle = label("截屏入账 · 本机识别", 13, MUTED, false);
        marginTop(subtitle, 3);
        title.addView(subtitle);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        boolean active = ScreenshotService.isActive();
        String status = active ? "●  识别中" : starting ? "正在开启" : "开启识别";
        TextView toggle = button(status, active ? 0xff146746 : BLUE, active ? 0xffdff5eb : TINT, this::monitorSheet);
        toggle.setMinHeight(dp(44));
        header.addView(toggle, new LinearLayout.LayoutParams(WRAP, WRAP));
        content.addView(header);
    }

    private void renderMonthSelector() {
        LinearLayout selector = row();
        selector.setPadding(dp(6), dp(4), dp(6), dp(4));
        selector.setBackground(shape(WHITE, 22));
        selector.addView(arrow("‹", "上个月", () -> changeMonth(-1), true));
        TextView date = label(month.format(MONTH_FORMAT), 16, INK, true);
        date.setGravity(Gravity.CENTER);
        selector.addView(date, new LinearLayout.LayoutParams(0, WRAP, 1));
        selector.addView(arrow("›", "下个月", () -> changeMonth(1), month.isBefore(YearMonth.now())));
        addCard(selector);
    }

    private void changeMonth(int direction) {
        YearMonth next = month.plusMonths(direction);
        if (next.isAfter(YearMonth.now())) return;
        month = next;
        positions.clear();
        day = next.equals(YearMonth.now()) ? LocalDate.now().getDayOfMonth() : 1;
        filter = null;
        loading = true;
        render(true);
        load(true);
    }

    private void renderHero(Report report) {
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff294bdd, 0xff4666f7, 0xff7490ff});
        glass.setCornerRadius(dp(27));
        hero.setBackground(glass);
        hero.setClipToOutline(true);
        hero.addView(new Charts.HeroWaves(this), new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout info = column();
        info.setPadding(dp(21), dp(19), dp(21), dp(20));
        info.addView(label("本月总支出", 14, 0xffedf1ff, true));
        TextView amount = label(Expense.money(report.total), 39, WHITE, true);
        amount.setSingleLine(true);
        amount.setAutoSizeTextTypeUniformWithConfiguration(19, 39, 1, TypedValue.COMPLEX_UNIT_SP);
        marginTop(amount, 5);
        info.addView(amount, new LinearLayout.LayoutParams(MATCH, dp(57)));
        LinearLayout metrics = row();
        metrics.addView(metric("消费笔数", report.expenses.size() + " 笔"));
        metrics.addView(metric("消费天数", report.spendingDays + " 天"));
        marginTop(metrics, 12);
        info.addView(metrics);
        hero.addView(info, new FrameLayout.LayoutParams(MATCH, WRAP));
        addCard(hero);
    }

    private LinearLayout metric(String title, String value) {
        LinearLayout metric = column();
        metric.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1));
        metric.addView(label(title, 12, 0xffe4ebff, false));
        TextView number = label(value, 18, WHITE, true);
        marginTop(number, 3);
        metric.addView(number);
        return metric;
    }

    private void renderCategories(Report report) {
        LinearLayout box = card();
        LinearLayout heading = row();
        heading.addView(label("消费分类", 20, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        heading.addView(button("＋ 分类", BLUE, TINT, this::addCategoryPage));
        box.addView(heading);
        box.addView(new Charts.Ring(this, report.categories, report.total), new LinearLayout.LayoutParams(MATCH, dp(205)));
        if (report.categories.isEmpty()) {
            box.addView(label("本月暂无记录。截一张付款成功页，识别结果会出现在这里。", 14, MUTED, false));
        } else {
            for (Map.Entry<Expense.Category, Long> item : report.categories.entrySet()) {
                Expense.Category category = item.getKey();
                long cents = item.getValue();
                LinearLayout line = row();
                line.setMinimumHeight(dp(52));
                TextView badge = badge(category);
                line.addView(badge);
                TextView name = label(category.label, 15, INK, true);
                name.setPadding(dp(10), 0, dp(6), 0);
                line.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
                String percent = String.format(Locale.CHINA, "%.0f%%", cents * 100.0 / report.total);
                line.addView(label(Expense.money(cents) + "  ·  " + percent, 13, MUTED, true));
                makeClickable(line, WHITE, 15, () -> {
                    pendingFilter = category;
                    switchPage(Page.DETAILS);
                });
                box.addView(line);
            }
        }
        addCard(box);
    }

    private void renderDays(Report report) {
        LinearLayout box = card();
        box.addView(label("每日支出", 20, INK, true));
        TextView helper = label("点选柱形查看当天的金额和交易", 13, MUTED, false);
        marginTop(helper, 5);
        box.addView(helper);

        LinearLayout selected = column();
        LinearLayout dayExpenses = card();
        Charts.Days chart = new Charts.Days(this, report.days, day, chosen -> {
            day = chosen;
            updateDay(selected, dayExpenses, report);
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(MATCH, dp(156));
        size.topMargin = dp(14);
        box.addView(chart, size);
        LinearLayout navigation = row();
        marginTop(navigation, 8);
        navigation.addView(arrow("‹", "前一天", () -> {
            if (day > 1) {
                day--;
                updateDay(selected, dayExpenses, report);
                chart.setSelectedDay(day);
            }
        }, true));
        navigation.addView(selected, new LinearLayout.LayoutParams(0, WRAP, 1));
        navigation.addView(arrow("›", "后一天", () -> {
            if (day < report.days.length) {
                day++;
                updateDay(selected, dayExpenses, report);
                chart.setSelectedDay(day);
            }
        }, true));
        box.addView(navigation);
        addCard(box);

        addCard(dayExpenses);
        updateDay(selected, dayExpenses, report);
    }

    private void updateDay(LinearLayout selected, LinearLayout dayExpenses, Report report) {
        selected.removeAllViews();
        LocalDate date = month.atDay(day);
        TextView label = label(date.format(DATE_FORMAT), 13, MUTED, false);
        label.setGravity(Gravity.CENTER);
        selected.addView(label);
        TextView amount = label(Expense.money(report.days[day - 1]), 24, INK, true);
        amount.setGravity(Gravity.CENTER);
        selected.addView(amount);

        dayExpenses.removeAllViews();
        List<Expense> rows = report.byDate.getOrDefault(date, List.of());
        dayExpenses.addView(label("当天交易", 18, INK, true));
        if (rows.isEmpty()) {
            TextView empty = label("当天暂无消费记录", 14, MUTED, false);
            marginTop(empty, 12);
            dayExpenses.addView(empty);
        } else {
            for (Expense expense : rows) dayExpenses.addView(expenseRow(expense));
        }
    }

    private void renderDetails(Report report) {
        if (filter != null) {
            LinearLayout strip = row();
            TextView title = label(filter.label + "明细", 18, INK, true);
            strip.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
            strip.addView(button("显示全部", BLUE, TINT, () -> {
                filter = null;
                render(true);
            }));
            addCard(strip);
        }

        boolean any = false;
        for (Map.Entry<LocalDate, List<Expense>> entry : report.byDate.entrySet()) {
            List<Expense> rows = entry.getValue().stream()
                    .filter(expense -> filter == null || expense.category().equals(filter))
                    .collect(java.util.stream.Collectors.toList());
            if (rows.isEmpty()) continue;
            any = true;
            LinearLayout group = card();
            LinearLayout heading = row();
            TextView date = label(entry.getKey().format(DATE_FORMAT), 14, INK, true);
            heading.addView(date, new LinearLayout.LayoutParams(0, WRAP, 1));
            long cents = rows.stream().mapToLong(Expense::cents).sum();
            heading.addView(label(Expense.money(cents), 13, MUTED, true));
            group.addView(heading);
            for (Expense expense : rows) group.addView(expenseRow(expense));
            addCard(group);
        }
        if (!any) {
            LinearLayout empty = card();
            empty.addView(label("暂无消费明细", 18, INK, true));
            TextView description = label("识别到的消费会按日期出现在这里。", 14, MUTED, false);
            marginTop(description, 8);
            empty.addView(description);
            addCard(empty);
        }
    }

    private LinearLayout expenseRow(Expense expense) {
        LinearLayout line = row();
        line.setMinimumHeight(dp(61));
        line.addView(badge(expense.category()));
        LinearLayout text = column();
        text.setPadding(dp(10), dp(5), dp(8), dp(5));
        TextView title = label(expense.merchant(), 15, INK, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);
        String time = Instant.ofEpochMilli(expense.paidAt()).atZone(ZoneId.systemDefault()).format(TIME_FORMAT);
        text.addView(label(expense.category().label + " · " + time, 12, MUTED, false));
        line.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1));
        line.addView(label(Expense.money(expense.cents()), 14, INK, true));
        makeClickable(line, WHITE, 15, () -> expenseSheet(expense));
        return line;
    }

    private TextView badge(Expense.Category category) {
        TextView view = label(category.mark, 14, INK, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(shape((category.color & 0x00ffffff) | 0x20000000, 13));
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        return view;
    }

    private void renderNav() {
        boolean drill = page == Page.DETAILS && filter != null;
        if (drill != drillBackRegistered) {
            if (drill) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, returnToCategories);
            else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(returnToCategories);
            drillBackRegistered = drill;
        }
        nav.removeAllViews();
        for (Page tab : Page.values()) {
            boolean selected = page == tab;
            TextView item = button(tab.label, selected ? BLUE : MUTED, selected ? TINT : 0xfffafbff, () -> {
                if (page == tab && filter == null) return;
                if (page == tab) {
                    filter = null;
                    render(true);
                } else {
                    switchPage(tab);
                }
            });
            item.setPadding(dp(4), dp(4), dp(4), dp(4));
            item.setSelected(selected);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(45), 1);
            size.setMargins(dp(2), 0, dp(2), 0);
            nav.addView(item, size);
        }
    }

    private void monitorSheet() {
        if (starting) return;
        boolean active = ScreenshotService.isActive();
        boolean selected = ScreenshotFolder.selected(this) != null;
        LinearLayout sheet = sheet(active ? "截图识别已开启" : "自动识别付款截图");
        addSheetText(sheet, active
                ? "新保存的截图会在本机识别。你也可以通过常驻通知随时停止。"
                : "请选择「截屏录屏」对应的截图目录。墨账只识别目录中新保存的图片，并将已完成的消费写入账本。");
        addSheetButton(sheet, active ? "停止识别" : selected ? "开启识别" : "选择截图目录", WHITE, BLUE, () -> {
            closeSheet();
            if (active) {
                ScreenshotFolder.setResumeEnabled(this, false);
                stopService(new Intent(this, ScreenshotService.class));
            } else if (!selected) {
                chooseScreenshotFolder();
            } else {
                requestNotificationsOrStart();
            }
        });
        if (!active && selected) {
            addSheetButton(sheet, "更换截图目录", BLUE, TINT, () -> {
                closeSheet();
                chooseScreenshotFolder();
            });
        }
        addSheetButton(sheet, "关闭", MUTED, BG, this::closeSheet);
    }

    private void chooseScreenshotFolder() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, SCREENSHOT_FOLDER);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SCREENSHOT_FOLDER || result != RESULT_OK) return;
        if (data != null && ScreenshotFolder.select(this, data.getData())) {
            requestNotificationsOrStart();
        } else {
            LinearLayout sheet = sheet("请选择截图目录");
            addSheetText(sheet, "请在手机存储里打开「截屏录屏」或 Screenshots，再点「使用此文件夹」。");
            addSheetButton(sheet, "重新选择", WHITE, BLUE, () -> {
                closeSheet();
                chooseScreenshotFolder();
            });
            addSheetButton(sheet, "关闭", MUTED, BG, this::closeSheet);
        }
    }

    private void requestNotificationsOrStart() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else {
            startMonitor();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATIONS) startMonitor();
    }

    private void startMonitor() {
        try {
            ScreenshotFolder.setResumeEnabled(this, true);
            if (startForegroundService(new Intent(this, ScreenshotService.class)) == null) {
                Toast.makeText(this, "启动截图识别失败", Toast.LENGTH_SHORT).show();
                return;
            }
            starting = true;
            render(false);
            frame.postDelayed(() -> {
                if (!starting || isDestroyed()) return;
                starting = false;
                render(false);
                if (!ScreenshotService.isActive()) {
                    Toast.makeText(this, "截图识别尚未启动，请重试", Toast.LENGTH_SHORT).show();
                }
            }, 6_000);
        } catch (IllegalStateException | SecurityException denied) {
            Toast.makeText(this, "启动截图识别失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void switchPage(Page next) {
        if (!pager.moving()) pager.goTo(next.ordinal());
    }

    private android.view.animation.Interpolator slideCurve() {
        return new android.view.animation.PathInterpolator(.2f, .78f, .2f, 1f);
    }

    private java.util.List<Expense.Category> categories() {
        var options = new java.util.ArrayList<>(java.util.Arrays.asList(Expense.Category.values()));
        getSharedPreferences("categories", MODE_PRIVATE)
                .getStringSet("names", java.util.Set.of()).stream().sorted()
                .map(Expense.Category::custom).map(category -> CategoryStyles.resolve(this, category))
                .forEach(options::add);
        return options;
    }

    private LinearLayout fullPage(String title) {
        removeOverlay();
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);
        ScrollView body = new ScrollView(this);
        body.setFillViewport(true);
        body.setVerticalScrollBarEnabled(false);
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(12), dp(20), dp(24));
        TextView back = button("‹  返回", BLUE, TINT, this::closeSheet);
        panel.addView(back, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView heading = label(title, 30, INK, true);
        marginTop(heading, 16);
        panel.addView(heading);
        body.addView(panel, new ScrollView.LayoutParams(MATCH, WRAP));
        overlay.addView(body, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        frame.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        overlay.setAccessibilityPaneTitle(title);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, dismiss);
        fullScreenOverlay = true;
        int width = frame.getWidth();
        overlay.setTranslationX(width);
        overlay.animate().translationX(0).setDuration(300).setInterpolator(slideCurve()).start();
        root.animate().translationX(-width * .16f).setDuration(300).setInterpolator(slideCurve()).start();
        return panel;
    }

    private void addCategoryPage() {
        LinearLayout panel = fullPage("新建消费分类");
        LinearLayout preview = row();
        preview.setPadding(dp(18), dp(20), dp(18), dp(20));
        marginTop(preview, 16);
        panel.addView(preview);
        TextView previewIcon = label(CategoryStyles.SYMBOLS[0], 32, WHITE, true);
        previewIcon.setGravity(Gravity.CENTER);
        previewIcon.setBackground(shape(0x30ffffff, 19));
        preview.addView(previewIcon, new LinearLayout.LayoutParams(dp(62), dp(62)));
        LinearLayout previewText = column();
        previewText.setPadding(dp(15), 0, 0, 0);
        TextView previewName = label("新分类", 23, WHITE, true);
        previewText.addView(previewName);
        previewText.addView(label("账本预览", 13, 0xfff0f1ff, false));
        preview.addView(previewText, new LinearLayout.LayoutParams(0, WRAP, 1));

        LinearLayout form = card();
        marginTop(form, 14);
        panel.addView(form);
        form.addView(label("名称", 16, INK, true));
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("例如旅行、宠物");
        input.setTextColor(INK);
        input.setBackground(shape(BG, 15));
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        marginTop(input, 8);
        form.addView(input);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                String name = text.toString().strip();
                previewName.setText(name.isEmpty() ? "新分类" : name);
            }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });

        int[] selection = {0, 0};
        LinearLayout palette = row();
        LinearLayout icons = row();
        Runnable update = () -> {
            int color = CategoryStyles.COLORS[selection[0]];
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{color, Color.rgb((Color.red(color) * 2 + 25) / 3,
                            (Color.green(color) * 2 + 33) / 3, (Color.blue(color) * 2 + 68) / 3)});
            gradient.setCornerRadius(dp(24));
            preview.setBackground(gradient);
            previewIcon.setText(CategoryStyles.SYMBOLS[selection[1]]);
            for (int i = 0; i < CategoryStyles.COLORS.length; i++) {
                TextView swatch = (TextView) palette.getChildAt(i);
                swatch.setText(i == selection[0] ? "✓" : "●");
                swatch.setTextColor(CategoryStyles.COLORS[i]);
                swatch.setBackground(shape(i == selection[0] ? TINT : BG, 14));
                swatch.setSelected(i == selection[0]);
            }
            for (int i = 0; i < CategoryStyles.SYMBOLS.length; i++) {
                TextView symbol = (TextView) icons.getChildAt(i);
                symbol.setTextColor(i == selection[1] ? color : MUTED);
                symbol.setBackground(shape(i == selection[1] ? TINT : BG, 14));
                symbol.setSelected(i == selection[1]);
            }
        };
        for (int i = 0; i < CategoryStyles.COLORS.length; i++) {
            int index = i;
            addStyleChoice(palette, "●", CategoryStyles.COLOR_NAMES[i], () -> {
                selection[0] = index;
                update.run();
            });
        }
        for (int i = 0; i < CategoryStyles.SYMBOLS.length; i++) {
            int index = i;
            addStyleChoice(icons, CategoryStyles.SYMBOLS[i], "图标 " + CategoryStyles.SYMBOLS[i], () -> {
                selection[1] = index;
                update.run();
            });
        }
        TextView colorLabel = label("颜色", 16, INK, true);
        marginTop(colorLabel, 17);
        form.addView(colorLabel);
        marginTop(palette, 8);
        form.addView(palette);
        TextView iconLabel = label("图标", 16, INK, true);
        marginTop(iconLabel, 17);
        form.addView(iconLabel);
        marginTop(icons, 8);
        form.addView(icons);
        update.run();

        addSheetButton(panel, "创建分类", WHITE, BLUE, () -> {
            String name = input.getText().toString().strip();
            if (name.isEmpty() || categories().stream().anyMatch(category -> category.label.equalsIgnoreCase(name))) {
                input.setError(name.isEmpty() ? "请输入分类名称" : "已有同名分类");
                return;
            }
            var preferences = getSharedPreferences("categories", MODE_PRIVATE);
            var names = new java.util.HashSet<>(preferences.getStringSet("names", java.util.Set.of()));
            names.add(name);
            preferences.edit().putStringSet("names", names)
                    .putInt("style:" + name, selection[0] * CategoryStyles.SYMBOLS.length + selection[1]).apply();
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
            closeSheet();
            render(false);
        });
    }

    private void addStyleChoice(LinearLayout row, String glyph, String description, Runnable choose) {
        TextView option = label(glyph, 22, INK, true);
        option.setGravity(Gravity.CENTER);
        option.setContentDescription(description);
        makeClickable(option, BG, 14, choose);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(44), 1);
        size.setMargins(dp(2), 0, dp(2), 0);
        row.addView(option, size);
    }

    private void expenseSheet(Expense expense) {
        LinearLayout panel = fullPage("消费详情");
        LinearLayout summary = card();
        marginTop(summary, 18);
        summary.addView(label(Expense.money(expense.cents()), 36, INK, true));
        addSheetText(summary, expense.merchant() + " · " + expense.source() + " · " + expense.date().format(DATE_FORMAT));
        panel.addView(summary);

        LinearLayout choices = card();
        marginTop(choices, 12);
        choices.addView(label("修改分类", 20, INK, true));
        for (Expense.Category option : categories()) {
            boolean selected = option.equals(expense.category());
            LinearLayout optionRow = row();
            optionRow.setPadding(dp(10), dp(7), dp(12), dp(7));
            optionRow.addView(badge(option));
            TextView name = label(option.label, 16, selected ? BLUE : INK, true);
            name.setPadding(dp(10), 0, 0, 0);
            optionRow.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
            if (selected) optionRow.addView(label("✓", 19, BLUE, true));
            makeClickable(optionRow, selected ? TINT : BG, 16, () -> {
                closeSheet();
                mutate(() -> Ledger.get(this).recategorize(expense.id(), option));
            });
            marginTop(optionRow, 7);
            choices.addView(optionRow);
        }
        panel.addView(choices);
        LinearLayout deletion = card();
        marginTop(deletion, 12);
        panel.addView(deletion);
        renderDeleteControl(deletion, expense);
    }

    private void renderDeleteControl(LinearLayout deletion, Expense expense) {
        deletion.removeAllViews();
        addSheetButton(deletion, "删除这笔记录", 0xffa63243, 0xffffedf0, () -> {
            deletion.removeAllViews();
            deletion.addView(label("删除这笔记录？", 17, INK, true));
            addSheetButton(deletion, "确认删除", WHITE, 0xffa63243, () -> {
                closeSheet();
                mutate(() -> Ledger.get(this).delete(expense.id()));
            });
            addSheetButton(deletion, "取消", MUTED, BG, () -> renderDeleteControl(deletion, expense));
        });
    }

    private void mutate(Runnable action) {
        io.execute(() -> {
            try {
                action.run();
                runOnUiThread(() -> {
                    if (!isDestroyed()) load(false);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) Toast.makeText(this, "未能保存修改", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private LinearLayout sheet(String title) {
        removeOverlay();
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x66161d36);
        overlay.setOnClickListener(view -> closeSheet());
        ScrollView container = new ScrollView(this);
        container.setFillViewport(false);
        container.setClickable(true);
        container.setVerticalScrollBarEnabled(false);
        container.setBackground(shape(WHITE, 28));
        container.setClipToOutline(true);

        LinearLayout panel = column();
        panel.setClickable(true);
        panel.setPadding(dp(20), dp(14), dp(20), dp(20));
        View handle = new View(this);
        handle.setBackground(shape(0xffdce1ed, 3));
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(dp(34), dp(4));
        handleSize.gravity = Gravity.CENTER_HORIZONTAL;
        handleSize.bottomMargin = dp(14);
        panel.addView(handle, handleSize);
        panel.addView(label(title, 23, INK, true));
        container.addView(panel, new ScrollView.LayoutParams(MATCH, WRAP));
        FrameLayout.LayoutParams panelSize = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        panelSize.setMargins(dp(12), dp(24), dp(12), dp(8));
        overlay.addView(container, panelSize);

        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        frame.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        overlay.setAccessibilityPaneTitle(title);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, dismiss);
        container.setTranslationY(dp(16));
        container.setAlpha(0f);
        container.animate().translationY(0).alpha(1f).setDuration(180).start();
        return panel;
    }

    private void closeSheet() {
        if (overlay == null || closingOverlay) return;
        if (!fullScreenOverlay || isDestroyed() || isFinishing()) {
            removeOverlay();
            return;
        }
        closingOverlay = true;
        overlay.animate().translationX(frame.getWidth()).setDuration(260)
                .setInterpolator(slideCurve()).withEndAction(this::removeOverlay).start();
        root.animate().translationX(0).setDuration(260).setInterpolator(slideCurve()).start();
    }

    private void removeOverlay() {
        if (overlay == null) return;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(dismiss);
        overlay.animate().cancel();
        frame.removeView(overlay);
        root.animate().cancel();
        root.setTranslationX(0);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        overlay = null;
        fullScreenOverlay = false;
        closingOverlay = false;
    }

    private void addSheetText(LinearLayout panel, String text) {
        TextView description = label(text, 15, MUTED, false);
        description.setLineSpacing(dp(3), 1);
        marginTop(description, 10);
        panel.addView(description);
    }

    private void addSheetButton(LinearLayout panel, String title, int foreground, int background, Runnable action) {
        TextView control = button(title, foreground, background, action);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(MATCH, WRAP);
        size.topMargin = dp(10);
        panel.addView(control, size);
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setBackground(shape(WHITE, 26));
        layout.setPadding(dp(18), dp(17), dp(18), dp(17));
        return layout;
    }

    private void addCard(View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(12);
        content.addView(view, params);
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(sp);
        label.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return label;
    }

    private TextView button(String text, int foreground, int background, Runnable action) {
        TextView label = label(text, 14, foreground, true);
        label.setGravity(Gravity.CENTER);
        label.setMinHeight(dp(44));
        label.setPadding(dp(12), dp(7), dp(12), dp(7));
        makeClickable(label, background, 20, action);
        return label;
    }

    private TextView arrow(String mark, String description, Runnable action, boolean enabled) {
        TextView view = label(mark, 26, enabled ? BLUE : 0xffb2b9ca, true);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(44)));
        if (enabled) makeClickable(view, BG, 17, action);
        else {
            view.setBackground(shape(BG, 17));
            view.setEnabled(false);
        }
        return view;
    }

    private void makeClickable(View view, int background, int radius, Runnable action) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x193760fa),
                shape(background, radius), shape(WHITE, radius)));
        view.setOnClickListener(clicked -> action.run());
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void marginTop(View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(gap);
        view.setLayoutParams(params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
