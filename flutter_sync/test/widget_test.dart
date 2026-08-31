// 冒烟测试：无服务可达时页面仍应正常构建（空态），且离开时取消轮询。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:flutter_sync/main.dart';
import 'package:flutter_sync/sync_controller.dart';
import 'package:flutter_sync/theme.dart';

void main() {
  testWidgets('SyncPage 渲染三个面板，服务不可达时展示空态', (tester) async {
    // 指向不存在的端口，所有请求快速失败
    final controller = SyncController();

    await tester.pumpWidget(MaterialApp(
      theme: buildSyncTheme(),
      home: SyncPage(controller: controller),
    ));

    // 等加载完成（连接被拒，毫秒级返回）
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('配对与设备'), findsOneWidget);
    expect(find.text('设置'), findsOneWidget);
    expect(find.text('显示报文'), findsOneWidget);

    // 「配对与设备」默认展开，展示空态文案
    expect(find.text('尚无已配对设备。'), findsOneWidget);

    // 卸载页面 → dispose 取消轮询（否则测试框架会报 pending timer）
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });
}
