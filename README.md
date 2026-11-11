# 墨账 InkLedger

在微信支付或支付宝的付款成功页、交易详情页截屏，墨账会尝试提取实付金额和商户，并把识别到的消费归入本地账本。界面采用参考 HyperOS 4 的圆角卡片、光影渐变和底部面板。

## 打开项目

用支持 AGP 9.2 的 Android Studio 打开项目根目录。项目使用内置 Gradle Wrapper（9.4.1）、Android SDK Platform 37（compileSdk / targetSdk 均为 37） 和 Android Studio 自带的 JDK（17 或更高）。运行环境为 Android 14 及以上，主要验证目标是小米 15、HyperOS 4、Android 17。

## 使用流程

1. 在墨账点「开启识别」，在系统文件选择器中选择手机存储里的「截屏录屏」目录（也可能显示为 Screenshots）。允许通知即可接收识别结果。
2. 在付款成功页或账单详情页使用系统截图，等待图片保存。识别只处理启动服务后新保存的截图，不需要手动分享。
3. 识别到成功交易且金额明确时，写入账本并显示金额、类别和商户。无法判断是否为消费时静默忽略。金额明确、类别不明确时记为「待分类」。
4. 在「分类」查看月度金额和占比，点分类筛选明细。在「每日」点柱形查看当天金额和交易。在「明细」按日期浏览，点任意交易可修正类别或删除。

系统可能清理后台进程。服务停止后，可以回到墨账重新开启。截图必须保存到选定的目录。如果「截屏录屏」是相册里的虚拟分类，请选择手机存储中实际保存截图的目录。应用只申请该目录的只读授权，并在打开文件之前按 MIME 类型过滤，只读取新保存的图片。Android 的目录授权本身覆盖目录中的各种文件类型，录屏视频由识别服务在打开前排除。对于交易页有订单号的交易，重复截图按交易号去重。其他交易只按同一截图文件去重，以免把两笔金额相同的消费合并。

截图 OCR 使用内置的 ML Kit 中文模型。应用不申请网络访问权限，不复制或保存截图及完整 OCR 文本。账本在本地 SQLite 中保存实付金额、商户、类别、来源和日期，以及用于去重的哈希。

## 小米超级岛

项目默认通过普通通知显示识别结果。已接入小米超级岛的应用可将以下配置放在用户级 Gradle 配置文件（Windows 通常为 `%USERPROFILE%\.gradle\gradle.properties`）中。

```properties
xiaomiAppId=小米平台分配的应用ID
xiaomiIslandBusiness=获批的业务场景
xiaomiDebug=true
```

使用测试签名时设 `xiaomiDebug=true`，正式签名构建使用 `false`。小米平台需要验证包名、签名和场景。代码会查询焦点通知能力并按小米模板附加岛通知数据。原生上岛有赖于小米平台授权以及手机的通知设置。

- [小米超级岛接入流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)
- [小米超级岛开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
- [Android 17 SDK 配置](https://developer.android.com/about/versions/17/setup-sdk)

## 当前验证状态

已用 Android SDK Platform 37.0 构建调试 APK，并通过两组付款和三组非付款文本样例的解析检查。小米 15 上的目录授权、截图 OCR、后台识别与 HyperOS 4 超级岛展示仍需真机验证。支付页面更新可能需要调整识别规则。下一轮需用脱敏的微信支付、支付宝付款成功页和普通截图验证。
